package fs2

import cats._
import cats.effect._
import fs2.internal._

/**
  * A `p: Pull[F,O,R]` reads values from one or more streams, returns a
  * result of type `R`, and produces a `Stream[F,O]` when calling `p.stream`.
  *
  * Any resources acquired by `p` are freed following the call to `stream`.
  *
  * Laws:
  *
  * `Pull` forms a monad in `R` with `pure` and `flatMap`:
  *   - `pure >=> f == f`
  *   - `f >=> pure == f`
  *   - `(f >=> g) >=> h == f >=> (g >=> h)`
  * where `f >=> g` is defined as `a => a flatMap f flatMap g`
  *
  * `raiseError` is caught by `handleErrorWith`:
  *   - `handleErrorWith(raiseError(e))(f) == f(e)`
  */
final class Pull[+F[_], +O, +R] private (private val free: FreeC[Algebra[Nothing, Nothing, ?], R])
    extends AnyVal {

  private[fs2] def get[F2[x] >: F[x], O2 >: O, R2 >: R]: FreeC[Algebra[F2, O2, ?], R2] =
    free.asInstanceOf[FreeC[Algebra[F2, O2, ?], R2]]

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    Pull.fromFreeC(get[F, O, R].map(r => Right(r)).handleErrorWith(t => FreeC.pure(Left(t))))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def stream: Stream[F, O] =
    Stream.fromFreeC(this.scope.get[F, O, Unit])

  /**
    * Like [[stream]] but no scope is inserted around the pull, resulting in any resources being
    * promoted to the current scope of the stream, extending the resource lifetime. Typically used
    * as a performance optimization, where resource lifetime can be extended in exchange for faster
    * execution.
    *
    * Caution: this can result in resources with greatly extended lifecycles if the pull
    * discards parts of the stream from which it was created. This could lead to memory leaks
    * so be very careful when using this function. For example, a pull that emits the first element
    * and discards the tail could discard the release of one or more resources that were acquired
    * in order to produce the first element. Normally, these resources would be registered in the
    * scope generated by the pull-to-stream conversion and hence released as part of that scope
    * closing but when using `streamNoScope`, they get promoted to the current stream scope,
    * which may be infinite in the worst case.
    */
  def streamNoScope: Stream[F, O] = Stream.fromFreeC(get[F, O, R].map(_ => ()))

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x] >: F[x], O2 >: O, R2](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    Pull.fromFreeC(get[F2, O2, R].flatMap(r => f(r).get))

  /** Alias for `flatMap(_ => p2)`. */
  def >>[F2[x] >: F[x], O2 >: O, R2](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => p2)

  /** Lifts this pull to the specified effect type. */
  def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this.asInstanceOf[Pull[F2, O, R]]

  /** Lifts this pull to the specified effect type, output type, and resource type. */
  def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Lifts this pull to the specified output type. */
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Lifts this pull to the specified resource type. */
  def covaryResource[R2 >: R]: Pull[F, O, R2] = this

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F, O, R2] = Pull.fromFreeC(get.map(f))

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R] = Pull.mapOutput(this)(f)

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> new Pull(Algebra.raiseError[Nothing, Nothing, Nothing](e))) >> p2

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    Pull.fromFreeC(get[F2, O2, R2].handleErrorWith(e => h(e).get))

  /** Tracks any resources acquired during this pull and releases them when the pull completes. */
  def scope: Pull[F, O, Unit] = Pull.fromFreeC(Algebra.scope(get[F, O, R].map(_ => ())))
}

object Pull extends PullLowPriority {

  @inline private[fs2] def fromFreeC[F[_], O, R](free: FreeC[Algebra[F, O, ?], R]): Pull[F, O, R] =
    new Pull(free.asInstanceOf[FreeC[Algebra[Nothing, Nothing, ?], R]])

  /** Result of `acquireCancellable`. */
  sealed abstract class Cancellable[+F[_], +R] {

    /** Cancels the cleanup of the resource (typically because the resource was manually cleaned up). */
    val cancel: Pull[F, INothing, Unit]

    /** Acquired resource. */
    val resource: R

    /** Returns a new cancellable with the same `cancel` pull but with the resource returned from applying `R` to `f`. */
    def map[R2](f: R => R2): Cancellable[F, R2]
  }
  object Cancellable {
    def apply[F[_], R](cancel0: Pull[F, INothing, Unit], r: R): Cancellable[F, R] =
      new Cancellable[F, R] {
        val cancel = cancel0
        val resource = r
        def map[R2](f: R => R2): Cancellable[F, R2] = apply(cancel, f(r))
      }
  }

  /**
    * Acquire a resource within a `Pull`. The cleanup action will be run at the end
    * of the `.stream` scope which executes the returned `Pull`. The acquired
    * resource is returned as the result value of the pull.
    */
  def acquire[F[_]: RaiseThrowable, R](r: F[R])(cleanup: R => F[Unit]): Pull[F, INothing, R] =
    acquireCancellable(r)(cleanup).map(_.resource)

  /**
    * Like [[acquire]] but the result value consists of a cancellation
    * pull and the acquired resource. Running the cancellation pull frees the resource.
    * This allows the acquired resource to be released earlier than at the end of the
    * containing pull scope.
    */
  def acquireCancellable[F[_]: RaiseThrowable, R](r: F[R])(
      cleanup: R => F[Unit]): Pull[F, INothing, Cancellable[F, R]] =
    acquireCancellableCase(r)((r, _) => cleanup(r))

  /**
    * Like [[acquireCancellable]] but provides an `ExitCase[Throwable]` to the `cleanup` action,
    * indicating the cause for cleanup execution.
    */
  def acquireCancellableCase[F[_]: RaiseThrowable, R](r: F[R])(
      cleanup: (R, ExitCase[Throwable]) => F[Unit]): Pull[F, INothing, Cancellable[F, R]] =
    Stream
      .bracketWithToken(r)(cleanup)
      .pull
      .uncons1
      .flatMap {
        case None                   => Pull.raiseError[F](new RuntimeException("impossible"))
        case Some(((token, r), tl)) => Pull.pure(Cancellable(release(token), r))
      }

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    fromFreeC(
      Algebra
        .eval[F, INothing, R](fr)
        .map(r => Right(r): Either[Throwable, R])
        .handleErrorWith(t => Algebra.pure[F, INothing, Either[Throwable, R]](Left(t))))

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Pure, INothing, Unit] =
    fromFreeC[Pure, INothing, Unit](Algebra.pure[Pure, INothing, Unit](()))

  /** Evaluates the supplied effectful value and returns the result as the resource of the returned pull. */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] =
    fromFreeC(Algebra.eval[F, INothing, R](fr))

  /**
    * Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](using: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => using(r).flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }

  private def mapOutput[F[_], O, O2, R](p: Pull[F, O, R])(f: O => O2): Pull[F, O2, R] =
    Pull.fromFreeC(
      p.get[F, O, R]
        .translate(new (Algebra[F, O, ?] ~> Algebra[F, O2, ?]) {
          def apply[X](in: Algebra[F, O, X]): Algebra[F, O2, X] = in match {
            case o: Algebra.Output[F, O] => Algebra.Output(o.values.map(f))
            case other                   => other.asInstanceOf[Algebra[F, O2, X]]
          }
        }))

  /** Outputs a single value. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] =
    fromFreeC(Algebra.output1[F, O](o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) Pull.done else fromFreeC(Algebra.output[F, O](os))

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] =
    fromFreeC(Algebra.pure(r))

  /**
    * Reads and outputs nothing, and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    new Pull(Algebra.raiseError[Nothing, Nothing, Nothing](err))

  final class PartiallyAppliedFromEither[F[_]] {
    def apply[A](either: Either[Throwable, A])(implicit ev: RaiseThrowable[F]): Pull[F, A, Unit] =
      either.fold(Pull.raiseError[F], Pull.output1)
  }

  /**
    * Lifts an Either[Throwable, A] to an effectful Pull[F, A, Unit].
    *
    * @example {{{
    * scala> import cats.effect.IO, scala.util.Try
    * scala> Pull.fromEither[IO](Right(42)).stream.compile.toList.unsafeRunSync()
    * res0: List[Int] = List(42)
    * scala> Try(Pull.fromEither[IO](Left(new RuntimeException)).stream.compile.toList.unsafeRunSync())
    * res1: Try[List[INothing]] = Failure(java.lang.RuntimeException)
    * }}}
    */
  def fromEither[F[x]] = new PartiallyAppliedFromEither[F]

  /**
    * Returns a pull that evaluates the supplied by-name each time the pull is used,
    * allowing use of a mutable value in pull computations.
    */
  def suspend[F[x] >: Pure[x], O, R](p: => Pull[F, O, R]): Pull[F, O, R] =
    fromFreeC(Algebra.suspend(p.get))

  private def release[F[x] >: Pure[x]](token: Token): Pull[F, INothing, Unit] =
    fromFreeC[F, INothing, Unit](Algebra.release(token, None))

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_], O](
      implicit ev: ApplicativeError[F, Throwable]): Sync[Pull[F, O, ?]] =
    new Sync[Pull[F, O, ?]] {
      def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      def handleErrorWith[A](p: Pull[F, O, A])(h: Throwable => Pull[F, O, A]) =
        p.handleErrorWith(h)
      def raiseError[A](t: Throwable) = Pull.raiseError[F](t)
      def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
      def suspend[R](p: => Pull[F, O, R]) = Pull.suspend(p)
      def bracketCase[A, B](acquire: Pull[F, O, A])(use: A => Pull[F, O, B])(
          release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]): Pull[F, O, B] =
        Pull.fromFreeC(
          FreeC
            .syncInstance[Algebra[F, O, ?]]
            .bracketCase(acquire.get)(a => use(a).get)((a, c) => release(a, c).get))
    }
}

private[fs2] trait PullLowPriority {
  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, ?]] =
    new Monad[Pull[F, O, ?]] {
      override def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      override def flatMap[A, B](p: Pull[F, O, A])(f: A ⇒ Pull[F, O, B]): Pull[F, O, B] =
        p.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A ⇒ Pull[F, O, Either[A, B]]): Pull[F, O, B] =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
    }
}
