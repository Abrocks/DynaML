package io.github.mandar2812.dynaml.kernels

import breeze.linalg.{DenseVector, DenseMatrix}

/**
 * Created by mandar on 18/8/15.
 */
class LinearKernel(private var degree: Int = 1,
                   private var offset: Double = 0.0)
  extends SVMKernel[DenseMatrix[Double]]
  with Serializable{

  override val hyper_parameters = List("offset")

  def setdegree(d: Int): Unit = {
    this.degree = d
  }

  def setoffset(o: Int): Unit = {
    this.offset = o
  }

  override def evaluate(x: DenseVector[Double], y: DenseVector[Double]): Double =
    Math.pow((x.t * y) + this.offset, this.degree)/(Math.pow((x.t * x) + this.offset,
      this.degree.toDouble/2.0) * Math.pow((y.t * y) + this.offset,
      this.degree.toDouble/2.0))

  override def buildKernelMatrix(
                                  mappedData: List[DenseVector[Double]],
                                  length: Int): KernelMatrix[DenseMatrix[Double]] =
    SVMKernel.buildSVMKernelMatrix(mappedData, length, this.evaluate)

  override def setHyperParameters(h: Map[String, Double]) = {
    assert(hyper_parameters.forall(h contains _),
      "All hyper parameters must be contained in the arguments")
    //this.degree = math.ceil(h("degree")).toInt
    this.offset = h("offset")
    this
  }
}
