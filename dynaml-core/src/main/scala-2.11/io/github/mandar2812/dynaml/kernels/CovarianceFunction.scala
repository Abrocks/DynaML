package io.github.mandar2812.dynaml.kernels

import breeze.linalg.{DenseMatrix, DenseVector}

/**
  * A covariance function implementation. Covariance functions are
  * central to Stochastic Process Models as well as SVMs.
  * */
abstract class CovarianceFunction[T, V, M] extends Kernel[T, V] {

  val hyper_parameters: List[String]

  var blocked_hyper_parameters: List[String] = List()

  var state: Map[String, Double] = Map()

  def effective_state:Map[String, Double] =
    state.filterNot(h => blocked_hyper_parameters.contains(h._1))

  def effective_hyper_parameters: List[String] =
    hyper_parameters.filterNot(h => blocked_hyper_parameters.contains(h))

  def setHyperParameters(h: Map[String, Double]): this.type = {
    assert(effective_hyper_parameters.forall(h.contains),
      "All hyper parameters must be contained in the arguments")
    effective_hyper_parameters.foreach((key) => {
      state += (key -> h(key))
    })
    this
  }

  def gradient(x: T, y: T): Map[String, V]

  def buildKernelMatrix[S <: Seq[T]](mappedData: S,
                                     length: Int): KernelMatrix[M]

  def buildCrossKernelMatrix[S <: Seq[T]](dataset1: S, dataset2: S): M

}

object CovarianceFunction {

  /**
    * Create a kernel from a feature mapping.
    * K(x,y) = phi^T^(x) . phi(y)
    *
    * @param phi A general non linear transformation from the domain to
    *            a multidimensional vector.
    *
    * @return A kernel instance defined for that particular feature transformation.
    * */
  def apply[T](phi: T => DenseVector[Double]) = new LocalScalarKernel[T] {
    override val hyper_parameters: List[String] = List()

    override def evaluate(x: T, y: T): Double = phi(x) dot phi(y)

    override def buildKernelMatrix[S <: Seq[T]](mappedData: S, length: Int): KernelMatrix[DenseMatrix[Double]] =
      SVMKernel.buildSVMKernelMatrix(mappedData, length, this.evaluate)

    override def buildCrossKernelMatrix[S <: Seq[T]](dataset1: S, dataset2: S): DenseMatrix[Double] =
      SVMKernel.crossKernelMatrix(dataset1, dataset2, this.evaluate)

  }

  /**
    * Create a kernel from a symmetric function.
    *
    * K(x,y) = f(state)(x,y)
    *
    * @param phi  A function which for every state outputs a symmetric kernel
    *             evaluation function for inputs.
    * @param s The (beginning) state of the kernel.
    *
    * */
  def apply[T](phi: Map[String, Double] => (T, T) => Double)(s: Map[String, Double]) =
    new LocalScalarKernel[T] {
      override val hyper_parameters: List[String] = s.keys.toList

      state = s

      override def evaluate(x: T, y: T): Double = phi(state)(x, y)

      override def buildKernelMatrix[S <: Seq[T]](mappedData: S, length: Int): KernelMatrix[DenseMatrix[Double]] =
        SVMKernel.buildSVMKernelMatrix(mappedData, length, this.evaluate)

      override def buildCrossKernelMatrix[S <: Seq[T]](dataset1: S, dataset2: S): DenseMatrix[Double] =
        SVMKernel.crossKernelMatrix(dataset1, dataset2, this.evaluate)

    }
}