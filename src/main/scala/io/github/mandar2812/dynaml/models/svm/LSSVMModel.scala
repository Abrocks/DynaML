package io.github.mandar2812.dynaml.models.svm

import breeze.linalg._
import breeze.numerics.{sqrt, sigmoid}
import com.github.tototoshi.csv.CSVReader
import com.tinkerpop.blueprints.util.io.graphml.GraphMLWriter
import com.tinkerpop.blueprints.util.io.graphson.GraphSONWriter
import com.tinkerpop.blueprints.{Graph, GraphFactory}
import com.tinkerpop.frames.{FramedGraph, FramedGraphFactory}
import io.github.mandar2812.dynaml.evaluation.Metrics
import io.github.mandar2812.dynaml.graphutils.{CausalEdge, Label, ParamEdge, Parameter, _}
import io.github.mandar2812.dynaml.kernels.SVMKernel
import io.github.mandar2812.dynaml.optimization._
import io.github.mandar2812.dynaml.utils
import org.apache.log4j.{Logger, Priority}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD

import scala.collection.JavaConversions._
import scala.collection.mutable

/**
 * Linear Model with conditional probability
 * of the target variable given the features
 * is a Gaussian with mean = wT.x.
 *
 * Gaussian priors on the parameters are imposed
 * as a means for L2 regularization.
 */

class LSSVMModel(
    override protected val g: FramedGraph[Graph],
    override protected val nPoints: Long,
    override protected val featuredims: Int,
    override protected val vertexMaps: (mutable.HashMap[String, AnyRef],
        mutable.HashMap[Long, AnyRef],
        mutable.HashMap[Long, AnyRef]),
    override protected val edgeMaps: (mutable.HashMap[Long, AnyRef],
      mutable.HashMap[Long, AnyRef]),
    override implicit protected val task: String)
  extends KernelLSSVMModel {

  override protected val logger = Logger.getLogger(this.getClass)

  override implicit protected var params =
    DenseVector.ones[Double](featuredims)

  override protected val optimizer = LSSVMModel.getOptimizer(task)

  private val sigmaInverse: DenseMatrix[Double] = inv(cholesky(variance))

  val rescale = LSSVMModel.scaleAttributes(this.mean, sigmaInverse) _

  def score(point: DenseVector[Double]): Double = {
    val rescaled = rescale(point)
    val phi = featureMap(rescaled)
    val phic = DenseVector.vertcat(phi, DenseVector(1.0))
    params dot phic
  }

  override def getRegParam: Double = this.optimizer.getRegParam

  override def predict(point: DenseVector[Double]): Double = task match {
    case "classification" => sigmoid(this.score(point))
    case "regression" => this.score(point)
  }

  override def evaluate(config: Map[String, String]): Metrics[Double, Double] = {
    val (file, delim, head, _) = LSSVMModel.readConfig(config)
    logger.log(Priority.INFO, "Calculating test set predictions")
    val reader = utils.getCSVReader(file, delim)
    val (points, dim) = LSSVMModel.readCSV(reader, head)
    var index = 1
    val scoreFunction = task match {
      case "classification" => this.score _
      case "regression" => (x: DenseVector[Double]) => {
        this.score(x)
      }
    }

    val scoresAndLabels = points.map{couple =>
      index += 1
      (scoreFunction(couple._1), couple._2)
    }.toList

    Metrics(task)(scoresAndLabels, index)
  }

  /**
   * Saves the underlying graph object
   * in the given file format Supported
   * formats include.
   *
   * 1) Graph JSON: "json"
   * 2) GraphML: "xml"
   * */
  def save(file: String, format: String = "json"): Unit = format match {
    case "json" => GraphSONWriter.outputGraph(this.g, file)
    case "xml" => GraphMLWriter.outputGraph(this.g, file)
  }

  def normalizeData: this.type = {
    logger.info("Rescaling data attributes")
    val xMap = this.vertexMaps._2
    val yMap = this.vertexMaps._3

    this.getXYEdges().foreach((edge) => {
      val xVertex: Point = edge.getPoint()
      val xvec: DenseVector[Double] = DenseVector(xVertex.getValue())
      val vec: DenseVector[Double] =
        rescale(xvec(0 to -2))
      xVertex.setValue(DenseVector.vertcat(vec, DenseVector(1.0)).toArray)

    })
    this
  }

  def GetStatistics(): Unit = {
    logger.info("Feature Statistics: \n")
    logger.info("Mean: "+this.mean)
    logger.info("Co-variance: \n"+this.variance)

    logger.info("Label Statistics: \n")
    logger.info("Mean: "+label_mean(0))
    logger.info("Variance: "+label_var(0,0))
  }

  override def evaluateFold(params: DenseVector[Double])
                           (test_data_set: Iterable[CausalEdge])
                           (task: String): Metrics[Double, Double] = {
    var index: Int = 1
    val scorepred: (DenseVector[Double]) => Double = params dot _
    val scoreFunction = task match {
      case "classification" => scorepred
      case "regression" => (x: DenseVector[Double]) => {
        scorepred(x)
      }
    }

    val scoresAndLabels = test_data_set.map((e) => {

      val x = DenseVector(e.getPoint().getFeatureMap())
      val y = e.getLabel().getValue()
      index += 1
      (scoreFunction(x), y)
    })
    Metrics(task)(scoresAndLabels.toList, index)
  }
}

object LSSVMModel {
  val manager: FramedGraphFactory = new FramedGraphFactory
  val logger = Logger.getLogger(this.getClass)

  /**
   * Factory function to rescale attributes
   * given a vector of means and the Cholesky
   * factorization of the inverse variance matrix
   *
   * */
  def scaleAttributes(mean: DenseVector[Double],
                      sigmaInverse: DenseMatrix[Double])(x: DenseVector[Double])
  : DenseVector[Double] = sigmaInverse * (x - mean)

  /**
   * Factory method to create the appropriate
   * optimization object required for the Gaussian
   * model
   * */
  def getOptimizer(task: String): ConjugateGradient = new ConjugateGradient

  def readCSV(reader: CSVReader, head: Boolean):
  (Iterable[(DenseVector[Double], Double)], Int) = {
    val stream = reader.toStream().toIterable
    val dim = stream.head.length

    def lines = if(head) {
      stream.drop(1)
    } else {
      stream
    }

    (lines.map{parseLine}, dim)
  }

  def parseLine = {line : List[String] =>
    //Parse line and extract features
    val yv = line.apply(line.length - 1).toDouble
    val xv: DenseVector[Double] =
      DenseVector(line.slice(0, line.length - 1).map{x => x.toDouble}.toArray)

    (xv, yv)
  }

  def readConfig(config: Map[String, String]): (String, Char, Boolean, String) = {

    assert(config.isDefinedAt("file"), "File name must be Defined!")
    val file: String = config("file")

    val delim: Char = if(config.isDefinedAt("delim")) {
      config("delim").toCharArray()(0)
    } else {
      ','
    }

    val head: Boolean = if(config.isDefinedAt("head")) {
      config("head") match {
        case "true" => true
        case "True" => true
        case "false" => false
        case "False" => false
      }
    } else {
      true
    }

    val task: String = if(config.isDefinedAt("task")) config("task") else ""

    (file, delim, head, task)
  }

  def apply(implicit config: Map[String, String]): LSSVMModel = {

    val (file, delim, head, task) = readConfig(config)
    val reader = utils.getCSVReader(file, delim)

    val graphconfig = Map("blueprints.graph" ->
      "com.tinkerpop.blueprints.impls.tg.TinkerGraph")

    val wMap: mutable.HashMap[String, AnyRef] = mutable.HashMap()
    val xMap: mutable.HashMap[Long, AnyRef] = mutable.HashMap()
    val yMap: mutable.HashMap[Long, AnyRef] = mutable.HashMap()
    val ceMap: mutable.HashMap[Long, AnyRef] = mutable.HashMap()
    val peMap: mutable.HashMap[Long, AnyRef] = mutable.HashMap()

    val fg = manager.create(GraphFactory.open(mapAsJavaMap(graphconfig)))

    var index = 1
    val (points, dim) = readCSV(reader, head)

    logger.log(Priority.INFO, "Creating graph for data set.")
    val pnode:Parameter = fg.addVertex(null, classOf[Parameter])
    pnode.setSlope(Array.fill[Double](dim)(1.0))
    wMap.put("w", pnode.asVertex().getId)

    points.foreach((couple) => {
      val xv = DenseVector.vertcat[Double](couple._1, DenseVector(Array(1.0)))
      val yv = couple._2
      /*
      * Create nodes xi and yi
      * append to them their values
      * properties, etc
      * */
      val xnode: Point = fg.addVertex(("x", index), classOf[Point])
      xnode.setValue(xv.toArray)
      xnode.setFeatureMap(xv.toArray)
      xMap.put(index, xnode.asVertex().getId)

      val ynode: Label = fg.addVertex(("y", index), classOf[Label])
      ynode.setValue(yv)
      yMap.put(index, ynode.asVertex().getId)

      //Add edge between xi and yi
      val ceEdge: CausalEdge = fg.addEdge((1, index), xnode.asVertex(),
        ynode.asVertex(), "causes",
        classOf[CausalEdge])
      ceEdge.setRelation("causal")
      ceMap.put(index, ceEdge.asEdge().getId)

      //Add edge between w and y_i
      val peEdge: ParamEdge = fg.addEdge((2, index), pnode.asVertex(),
        ynode.asVertex(), "controls", classOf[ParamEdge])
      peMap.put(index, peEdge.asEdge().getId)

      index += 1
    })

    val vMaps = (wMap, xMap, yMap)
    val eMaps = (ceMap, peMap)
    logger.log(Priority.INFO, "Graph constructed, now building model object.")
    new LSSVMModel(fg, index-1, dim, vMaps, eMaps, task).normalizeData
  }
}
