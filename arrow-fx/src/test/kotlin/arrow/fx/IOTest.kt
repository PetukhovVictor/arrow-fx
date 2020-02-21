package arrow.fx

import arrow.Kind
import arrow.core.Either
import arrow.core.Left
import arrow.core.None
import arrow.core.Right
import arrow.core.Some
import arrow.core.Tuple4
import arrow.core.identity
import arrow.core.right
import arrow.fx.IO.Companion.just
import arrow.fx.extensions.fx
import arrow.fx.extensions.io.async.async
import arrow.fx.extensions.io.concurrent.concurrent
import arrow.fx.extensions.io.concurrent.parMapN
import arrow.fx.extensions.io.dispatchers.dispatchers
import arrow.fx.extensions.toIO
import arrow.fx.extensions.toIOException
import arrow.fx.internal.parMap2
import arrow.fx.internal.parMap3
import arrow.fx.typeclasses.ExitCase2
import arrow.fx.typeclasses.milliseconds
import arrow.fx.typeclasses.seconds
import arrow.test.UnitSpec
import arrow.test.concurrency.SideEffect
import arrow.test.generators.GenK
import arrow.test.generators.throwable
import arrow.typeclasses.Eq
import arrow.typeclasses.EqK
import io.kotlintest.fail
import io.kotlintest.properties.Gen
import io.kotlintest.properties.forAll
import io.kotlintest.shouldBe
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.newSingleThreadContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.EmptyCoroutineContext

@kotlinx.coroutines.ObsoleteCoroutinesApi
class IOTest : UnitSpec() {

  private val other = newSingleThreadContext("other")
  private val all = newSingleThreadContext("all")
  private val NonBlocking = IO.dispatchers<Nothing>().default()

  init {
    // Broken due to version conflict... ?
    // testLaws(ConcurrentLaws.laws(IO.concurrent<Nothing>(), IO.timer<Nothing>(), IO.functor<Nothing>(), IO.applicative<Nothing>(), IO.monad<Nothing>(), IO.genK(), IO.eqK<Nothing>()))

    "should defer evaluation until run" {
      var run = false
      val ioa = IO { run = true }
      run shouldBe false
      ioa.unsafeRunSync()
      run shouldBe true
    }

    class MyException : Exception()

    "should catch exceptions within main block" {
      val exception = MyException()
      val ioa = IO { throw exception }
      val result: Either<Throwable, Nothing> =
        ioa.attempt().unsafeRunSync()

      val expected = Left(exception)

      result shouldBe expected
    }

    "should yield immediate successful invoke value" {
      val run = IO { 1 }.unsafeRunSync()

      val expected = 1

      run shouldBe expected
    }

    "should yield immediate successful effect value" {
      val run = IO.effect { 1 }.unsafeRunSync()

      val expected = 1

      run shouldBe expected
    }

    "should yield immediate successful pure value" {
      val run = just(1).unsafeRunSync()

      val expected = 1

      run shouldBe expected
    }

    "should throw immediate failure by raiseError" {
      try {
        IO.raiseException<Int>(MyException()).unsafeRunSync()
        fail("")
      } catch (myException: MyException) {
        // Success
      } catch (throwable: Throwable) {
        fail("Should only throw MyException")
      }
    }

    "should return immediate value by uncancellable" {
      val run = just(1).uncancellable().unsafeRunSync()

      val expected = 1

      run shouldBe expected
    }

    "should time out on unending unsafeRunTimed" {
      val never = IO.async<Nothing>().never<Int>().fix()
      val start = System.currentTimeMillis()
      val received = never.unsafeRunTimed(100.milliseconds)
      val elapsed = System.currentTimeMillis() - start

      received shouldBe None
      (elapsed >= 100) shouldBe true
    }

    "should return an Error value from unsafeRunTimed" {
      val someError = "domain error"
      val failure = IO.raiseError<String, Int?>(someError)
      val received = failure.unsafeRunTimed(100.milliseconds)

      received shouldBe Some(Left(someError))
    }

    "should return a null value from unsafeRunTimed" {
      val never = just<Int?>(null)
      val received = never.unsafeRunTimed(100.milliseconds)

      received shouldBe Some(Right(null))
    }

    "should return a null value from unsafeRunSync" {
      val value = just<Int?>(null).unsafeRunSync()

      value shouldBe null
    }

    "should complete when running a pure value with unsafeRunAsync" {
      val expected = 0
      just(expected).unsafeRunAsync { either ->
        either.fold({ fail("") }, { it shouldBe expected })
      }
    }

    "should complete when running a return value with unsafeRunAsync" {
      val expected = 0
      IO { expected }.unsafeRunAsync { either ->
        either.fold({ fail("") }, { it shouldBe expected })
      }
    }

    "should return an error when running an exception with unsafeRunAsync" {
      IO.raiseException<Int>(MyException()).unsafeRunAsync { either ->
        either.fold({
          when (it) {
            is MyException -> {
            }
            else -> fail("Should only throw MyException")
          }
        }, { fail("") })
      }
    }

    "should return exceptions within main block with unsafeRunAsync" {
      val exception = MyException()
      val ioa = IO<Int> { throw exception }
      ioa.unsafeRunAsync { either ->
        either.fold({ it shouldBe exception }, { fail("") })
      }
    }

    "should return exceptions within main block with unsafeRunAsyncCancellable" {
      val exception = MyException()
      val ioa = IO<Int> { throw exception }
      ioa.unsafeRunAsyncCancellable { either ->
        either.fold({ it shouldBe exception }, { fail("") })
      }
    }

    "should rethrow exceptions within run block with unsafeRunAsync" {
      try {
        val exception = MyException()
        val ioa = IO<Int> { throw exception }
        ioa.unsafeRunAsync { either ->
          either.fold({ throw it }, { fail("") })
        }
        fail("Should rethrow the exception")
      } catch (myException: MyException) {
        // Success
      } catch (throwable: Throwable) {
        fail("Should only throw MyException but was $throwable")
      }
    }

    "should rethrow exceptions within run block with unsafeRunAsyncCancellable" {
      try {
        val exception = MyException()
        val ioa = IO<Int> { throw exception }
        ioa.unsafeRunAsyncCancellable { either ->
          either.fold({ throw it }, { fail("") })
        }
        fail("Should rethrow the exception")
      } catch (myException: MyException) {
        // Success
      } catch (throwable: Throwable) {
        fail("Should only throw MyException but was $throwable")
      }
    }

    "should return an error when running an exception with runAsync" {
      IO.raiseException<Int>(MyException()).runAsync { either ->
        either.fold({
          when (it) {
            is MyException -> {
              IO { }
            }
            else -> fail("Should only throw MyException")
          }
        }, { fail("") }, { fail("") })
      }.unsafeRunSyncEither()
    }

    "should return exceptions within main block with runAsync" {
      val exception = MyException()
      val ioa = IO { throw exception }
      ioa.runAsync { either ->
        either.fold({ IO { it shouldBe exception } }, { fail("") }, { fail("") })
      }.unsafeRunSyncEither()
    }

    "should rethrow exceptions within run block with runAsync" {
      try {
        val exception = MyException()
        val ioa = IO { throw exception }
        ioa.runAsync { either ->
          either.fold({ throw it }, { fail("") }, { fail("") })
        }.unsafeRunSync()
        fail("Should rethrow the exception")
      } catch (throwable: AssertionError) {
        fail("${throwable.message}")
      } catch (throwable: Throwable) {
        // Success
      }
    }

    "should map values correctly on success" {
      val run = just(1).map { it + 1 }.unsafeRunSync()

      val expected = 2

      run shouldBe expected
    }

    "should flatMap values correctly on success" {
      val run = just(1).flatMap { num -> IO { num + 1 } }.unsafeRunSync()

      val expected = 2

      run shouldBe expected
    }

    "invoke is called on every run call" {
      val sideEffect = SideEffect()
      val io = IO { sideEffect.increment(); 1 }
      io.unsafeRunSync()
      io.unsafeRunSync()

      sideEffect.counter shouldBe 2
    }

    "effect is called on every run call" {
      val sideEffect = SideEffect()
      val io = IO.effect { sideEffect.increment(); 1 }
      io.unsafeRunSync()
      io.unsafeRunSync()

      sideEffect.counter shouldBe 2
    }

    "effect is called on the correct ctx" {
      val io = IO.effect(all) { Thread.currentThread().name }
      io.unsafeRunSync() shouldBe "all"
    }

    "CoroutineContext state should be correctly managed between boundaries" {
      val ctxA = TestContext()
      val ctxB = CoroutineName("ctxB")
      // We have to explicitly reference kotlin.coroutines.coroutineContext since `TestContext` overrides this property.
      IO.effect { kotlin.coroutines.coroutineContext shouldBe EmptyCoroutineContext }
        .continueOn(ctxA)
        .flatMap { IO.effect { kotlin.coroutines.coroutineContext shouldBe ctxA } }
        .continueOn(ctxB)
        .flatMap { IO.effect { kotlin.coroutines.coroutineContext shouldBe ctxB } }
        .unsafeRunSync()
    }

    "fx should defer evaluation until run" {
      var run = false
      val program = IO.fx<Nothing, Unit> {
        run = true
      }

      run shouldBe false
      program.unsafeRunSync()
      run shouldBe true
    }

    "fx can switch execution context state across not/bind" {
      val program = IO.fx<Nothing, Unit> {
        val ctx = !IO.effect { kotlin.coroutines.coroutineContext }
        !IO.effect { ctx shouldBe EmptyCoroutineContext }
        continueOn(all)
        val ctx2 = !IO.effect { Thread.currentThread().name }
        !IO.effect { ctx2 shouldBe "all" }
      }

      program.unsafeRunSync()
    }

    "fx can pass context state across not/bind" {
      val program = IO.fx<Nothing, Unit> {
        val ctx = !IO.effect { kotlin.coroutines.coroutineContext }
        !IO.effect { ctx shouldBe EmptyCoroutineContext }
        continueOn(CoroutineName("Simon"))
        val ctx2 = !IO.effect { kotlin.coroutines.coroutineContext }
        !IO.effect { ctx2 shouldBe CoroutineName("Simon") }
      }

      program.unsafeRunSync()
    }

    "fx will respect thread switching across not/bind" {
      val program = IO.fx<Nothing, Unit> {
        continueOn(all)
        val initialThread = !IO.effect { Thread.currentThread().name }
        !(0..130).map { i -> IO.effect { i } }.parSequence()
        val continuedThread = !IO.effect { Thread.currentThread().name }
        continuedThread shouldBe initialThread
      }

      program.unsafeRunSync()
    }

    "unsafeRunTimed times out with None result" {
      val never = IO.async<Nothing>().never<Unit>().fix()
      val result = never.unsafeRunTimed(100.milliseconds)
      result shouldBe None
    }

    "parallel execution with single threaded context makes all IOs start at the same time" {
      val order = mutableListOf<Long>()

      fun makePar(num: Long): IO<Nothing, Long> =
        IO(newSingleThreadContext("$num")) {
          // Sleep according to my number
          Thread.sleep(num * 100)
        }.map {
          // Add myself to order list
          order.add(num)
          num
        }

      val result =
        all.parMapN(
          makePar(6), makePar(3), makePar(2), makePar(4), makePar(1), makePar(5)) { six, tree, two, four, one, five -> listOf(six, tree, two, four, one, five) }
          .unsafeRunSync()

      result shouldBe listOf(6L, 3, 2, 4, 1, 5)
      order.toList() shouldBe listOf(1L, 2, 3, 4, 5, 6)
    }

    "parallel execution preserves order for synchronous IOs" {
      val order = mutableListOf<Long>()

      fun IO<Nothing, Long>.order() =
        map {
          order.add(it)
          it
        }

      fun makePar(num: Long): IO<Nothing, Long> =
        IO.sleep((num * 100).milliseconds)
          .map { num }.order()

      val result =
        all.parMapN(
          makePar(6), just(1L).order(), makePar(4), IO.defer { just(2L) }.order(), makePar(5), IO { 3L }.order()) { six, one, four, two, five, three -> listOf(six, one, four, two, five, three) }
          .unsafeRunSync()

      result shouldBe listOf(6L, 1, 4, 2, 5, 3)
      order.toList() shouldBe listOf(1L, 2, 3, 4, 5, 6)
    }

    "Races are scheduled in the correct order" {
      val order = mutableListOf<Int>()

      fun makePar(num: Int): IO<Nothing, Int> =
        IO.effect {
          order.add(num)
        }.followedBy(IO.sleep((num * 200L).milliseconds))
          .map { num }

      val result = IO.raceN(
        all,
        makePar(9),
        makePar(8),
        makePar(7),
        makePar(6),
        makePar(5),
        makePar(4),
        makePar(3),
        makePar(2),
        makePar(1)
      ).unsafeRunSync()

      result shouldBe Race9.Ninth(1)
      order shouldBe listOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
    }

    "parallel mapping is done in the expected CoroutineContext" {
      fun makePar(num: Long) =
        IO(newSingleThreadContext("$num")) {
          // Sleep according to my number
          Thread.sleep(num * 100)
          num
        }

      val result =
        all.parMapN(
          makePar(6), just(1L), makePar(4), IO.defer { just(2L) }, makePar(5), IO { 3L }) { _, _, _, _, _, _ ->
          Thread.currentThread().name
        }.unsafeRunSync()

      // Will always result in "6" since it will always finish last (sleeps longest by makePar).
      result shouldBe "6"
    }

    "parallel IO#defer, IO#suspend and IO#async are run in the expected CoroutineContext" {
      val result =
        all.parMapN(
          IO { Thread.currentThread().name },
          IO.defer { just(Thread.currentThread().name) },
          IO.async<Nothing, String> { cb -> cb(IOResult.Success(Thread.currentThread().name)) },
          IO(other) { Thread.currentThread().name },
          ::Tuple4)
          .unsafeRunSync()

      result shouldBe Tuple4("all", "all", "all", "other")
    }

    "unsafeRunAsyncCancellable should cancel correctly" {
      IO.async<Nothing, Int> { cb ->
        val cancel =
          IO(all) { }
            .flatMap { IO.async<Nothing, Int> { cb -> Thread.sleep(500); cb(IOResult.Success(1)) } }
            .unsafeRunAsyncCancellableEither(OnCancel.Silent) {
              cb(it)
            }
        IO(other) { }
          .unsafeRunAsync { cancel() }
      }.unsafeRunTimed(2.seconds) shouldBe None
    }

    "unsafeRunAsyncCancellable should throw the appropriate exception" {
      IO.async<Nothing, Throwable> { cb ->
        val cancel =
          IO(all) { }
            .flatMap { IO.async<Nothing, Int> { cb -> Thread.sleep(500); cb(IOResult.Success(1)) } }
            .unsafeRunAsyncCancellable(OnCancel.ThrowCancellationException) {
              it.fold({ t -> cb(IOResult.Success(t)) }, { })
            }
        IO(other) { }
          .unsafeRunAsync { cancel() }
      }.unsafeRunTimed(2.seconds) shouldBe Some(Right(OnCancel.CancellationException))
    }

    "IOFrame should always be called when using IO.Bind" {
      val ThrowableAsStringFrame = object : IOFrame<Any?, Any?, IOOf<Nothing, String>> {
        override fun invoke(a: Any?) = just(a.toString())

        override fun recover(e: Throwable) = just(e.message ?: "")

        override fun handleError(e: Any?) = just(e.toString())
      }

      forAll(Gen.string()) { message ->
        IO.Bind(IO.raiseException(RuntimeException(message)), ThrowableAsStringFrame as (Int) -> IO<Nothing, String>)
          .unsafeRunSync() == message
      }
    }

    "unsafeRunAsyncCancellable can cancel even for infinite asyncs" {
      IO.async<Nothing, Int> { cb ->
        val cancel =
          IO(all) { }
            .flatMap { IO.async<Nothing, Int> { Thread.sleep(5000); } }
            .unsafeRunAsyncCancellableEither(OnCancel.ThrowCancellationException) {
              cb(it)
            }
        IO(other) { Thread.sleep(500); }
          .unsafeRunAsync { cancel() }
      }.unsafeRunTimed(2.seconds) shouldBe None
    }

    "IO.binding should for comprehend over IO" {
      val result = IO.fx<Nothing, Int> {
        val x = !IO.just(1)
        val y = !IO { x + 1 }
        y
      }.fix()
      result.unsafeRunSync() shouldBe 2
    }

    "IO bracket cancellation should release resource with cancel exit status" {
      IO.fx<Nothing, ExitCase2<Throwable>> {
        val p = !Promise<ExitCase2<Throwable>>()
        IO.just(0L).bracketCase(
          use = { IO.never },
          release = { _, exitCase -> p.complete(exitCase) }
        )
          .unsafeRunAsyncCancellable { }
          .invoke() // cancel immediately

        !p.get()
      }.unsafeRunSync() shouldBe ExitCase2.Cancelled
    }

    "Cancellable should run CancelToken" {
      IO.fx<Nothing, Unit> {
        val p = !Promise<Unit>()
        IO.cancellable<Nothing, Unit> {
          p.complete(Unit)
        }.unsafeRunAsyncCancellable { }
          .invoke()

        !p.get()
      }.unsafeRunSync() shouldBe Unit
    }

    "CancellableF should run CancelToken" {
      IO.fx<Nothing, Unit> {
        val p = !Promise<Unit>()
        IO.cancellableF<Nothing, Unit> {
          IO { p.complete(Unit) }
        }.unsafeRunAsyncCancellable { }
          .invoke()

        !p.get()
      }.unsafeRunSync() shouldBe Unit
    }

    "IO should cancel cancellable on dispose" {
      Promise.uncancellable<IOPartialOf<Nothing>, Unit>(IO.async()).flatMap { latch ->
        IO {
          IO.cancellable<Nothing, Unit> {
            latch.complete(Unit)
          }.unsafeRunAsyncCancellable { }
            .invoke()
        }.flatMap { latch.get() }
      }.unsafeRunSync()
    }

    "guarantee should be called on finish with error" {
      IO.fx<Nothing, Unit> {
        val p = !Promise<Unit>()
        IO.effect { throw Exception() }.guarantee(p.complete(Unit)).attempt().bind()
        !p.get()
      }.unsafeRunTimed(1.seconds) shouldBe Some(Right(Unit))
    }

    "Bracket should be stack safe" {
      val size = 20_000

      fun ioBracketLoop(i: Int): IO<Nothing, Int> =
        IO.unit.bracket(use = { just(i + 1) }, release = { IO.unit }).flatMap { ii ->
          if (ii < size) ioBracketLoop(ii)
          else just(ii)
        }

      just(1).flatMap { ioBracketLoop(0) }.unsafeRunSync() shouldBe size
    }

    "GuaranteeCase should be stack safe" {
      val size = 20_000

      fun ioGuaranteeCase(i: Int): IO<Nothing, Int> =
        IO.unit.guaranteeCase { IO.unit }.flatMap {
          val ii = i + 1
          if (ii < size) ioGuaranteeCase(ii)
          else just(ii)
        }

      just(1).flatMap { ioGuaranteeCase(0) }.unsafeRunSync() shouldBe size
    }

    "Async should be stack safe" {
      val size = 20_000

      fun ioAsync(i: Int): IO<Nothing, Int> = IO.async<Nothing, Int> { cb ->
        cb(IOResult.Success(i))
      }.flatMap { ii ->
        if (ii < size) ioAsync(ii + 1)
        else just(ii)
      }

      just(1).flatMap(::ioAsync).unsafeRunSync() shouldBe size
    }

    "forked pair race should run" {
      IO.fx<Nothing, Either<Int, Int>> {
        IO.raceN(
          IO.sleep(10.seconds).followedBy(IO.effect { 1 }),
          IO.effect { 3 }
        ).fork().bind().join().bind()
      }.unsafeRunSync() shouldBe 3.right()
    }

    "forked race3 should run" {
      IO.fx<Nothing, Race3<out Int, out Int, out Int>> {
        IO.raceN(
          IO.sleep(10.seconds).followedBy(IO.effect { 1 }),
          IO.sleep(10.seconds).followedBy(IO.effect { 3 }),
          IO.effect { 2 }
        ).fork().bind().join().bind()
      }.unsafeRunSync() shouldBe Race3.Third(2)
    }

    "forked racePair should run" {
      IO.fx<Nothing, Int> {
        val res = IO.racePair(
          IO.dispatchers<Nothing>().default(),
          IO.sleep(10.seconds).followedBy(IO.effect { 2 }),
          IO.effect { 1 }
        ).fork().bind().join().bind()

        res.fold(
          { a, _ -> a },
          { _, b -> b }
        )
      }.suspended() shouldBe Right(1)
    }

    "forked triple racePair should run" {
      IO.fx<Nothing, Int> {
        val res = IO.raceTriple(
          IO.dispatchers<Nothing>().default(),
          IO.sleep(1.seconds).followedBy(IO.effect { 1 }),
          IO.sleep(2.seconds).followedBy(IO.effect { 3 }),
          IO.effect { 2 }
        ).fork().bind().join().bind()

        res.fold(
          { a, _, _ -> a },
          { _, b, _ -> b },
          { _, _, c -> c }
        )
      }.suspended() shouldBe Right(2)
    }

    "IOParMap2 should be stack safe" {
      val size = 5000

      fun ioParMap2(i: Int): IO<Nothing, Int> =
        IO.parMapN(just(i), if (i < size) ioParMap2(i + 1) else just(i)) { _, ii -> ii }

      just(1).flatMap(::ioParMap2).unsafeRunSync() shouldBe size
    }

    "IOParMap3 should be stack safe" {
      val size = 5000

      fun ioParMap3(i: Int): IO<Nothing, Int> =
        IO.parMapN(just(i), IO.unit, if (i < size) ioParMap3(i + 1) else just(i)) { _, _, ii -> ii }

      just(1).flatMap(::ioParMap3).unsafeRunSync() shouldBe size
    }

    "IOParMap2 left handles null" {
      IO.parMapN(just<Int?>(null), IO.unit) { _, unit -> unit }
        .unsafeRunSync() shouldBe Unit
    }

    "IOParMap2 right handles null" {
      IO.parMapN(IO.unit, IO.just<Int?>(null)) { unit, _ -> unit }
        .unsafeRunSync() shouldBe Unit
    }

    "IOParMap3 left handles null" {
      IO.parMapN(just<Int?>(null), IO.unit, IO.unit) { _, unit, _ -> unit }
        .unsafeRunSync() shouldBe Unit
    }

    "IOParMap3 middle handles null" {
      IO.parMapN(IO.unit, IO.just<Int?>(null), IO.unit) { unit, _, _ -> unit }
        .unsafeRunSync() shouldBe Unit
    }

    "IOParMap3 right handles null" {
      IO.parMapN(IO.unit, IO.unit, IO.just<Int?>(null)) { unit, _, _ -> unit }
        .unsafeRunSync() shouldBe Unit
    }

    "ConcurrentParMap2 left handles null" {
      IO.concurrent<Nothing>().parMap2(NonBlocking, IO.just<Int?>(null), IO.unit) { _, unit -> unit }
        .fix().unsafeRunSync() shouldBe Unit
    }

    "ConcurrentParMap2 right handles null" {
      IO.concurrent<Nothing>().parMap2(NonBlocking, IO.unit, IO.just<Int?>(null)) { unit, _ -> unit }
        .fix().unsafeRunSync() shouldBe Unit
    }

    "ConcurrentParMap3 left handles null" {
      IO.concurrent<Nothing>().parMap3(NonBlocking, IO.just<Int?>(null), IO.unit, IO.unit) { _, unit, _ -> unit }
        .fix().unsafeRunSync() shouldBe Unit
    }

    "ConcurrentParMap3 middle handles null" {
      IO.concurrent<Nothing>().parMap3(NonBlocking, IO.unit, IO.just<Int?>(null), IO.unit) { unit, _, _ -> unit }
        .fix().unsafeRunSync() shouldBe Unit
    }

    "ConcurrentParMap3 right handles null" {
      IO.concurrent<Nothing>().parMap3(NonBlocking, IO.unit, IO.unit, IO.just<Int?>(null)) { unit, _, _ -> unit }
        .fix().unsafeRunSync() shouldBe Unit
    }

    "can go from Either to IO directly when Left type is a Throwable" {
      val exception = RuntimeException()
      val left = Either.left(exception)
      val right = Either.right("rightValue")

      left
        .toIO()
        .unsafeRunSyncEither() shouldBe Left(exception)
      right
        .toIO()
        .unsafeRunSync() shouldBe "rightValue"
    }

    "can go from Either to IO by mapping the Left value to a IO exception" {

      val exception = RuntimeException()
      val left = Either.left(exception)
      val right = Either.right("rightValue")

      left
        .toIOException()
        .attempt()
        .unsafeRunSync() shouldBe Left(exception)
      right
        .toIOException()
        .unsafeRunSync() shouldBe "rightValue"
    }

    "Cancellation is wired across suspend" {
      fun infiniteLoop(): IO<Nothing, Unit> {
        fun loop(iterations: Int): IO<Nothing, Unit> =
          just(iterations).flatMap { i -> loop(i + 1) }

        return loop(0)
      }

      val wrappedInfiniteLoop: IO<Nothing, Unit> =
        IO.effect { infiniteLoop().suspended().fold(::identity, ::identity) }

      IO.fx<Nothing, Unit> {
        val p = !Promise<ExitCase2<Nothing>>()
        val (_, cancel) = !IO.unit.bracketCase(
          release = { _, ec -> p.complete(ec) },
          use = { wrappedInfiniteLoop }
        ).fork()
        !IO.sleep(100.milliseconds)
        !cancel
        val result = !p.get()
        !IO.effect { result shouldBe ExitCase2.Cancelled }
      }.suspended()
    }
  }
}

/** Represents a unique identifier context using object equality. */
internal class TestContext : AbstractCoroutineContextElement(TestContext) {
  companion object Key : kotlin.coroutines.CoroutineContext.Key<CoroutineName>

  override fun toString(): String = "TestContext(${Integer.toHexString(hashCode())})"
}

internal fun <E> IO.Companion.eqK() = object : EqK<IOPartialOf<E>> {
  override fun <A> Kind<IOPartialOf<E>, A>.eqK(other: Kind<IOPartialOf<E>, A>, EQ: Eq<A>): Boolean = EQ<E, A>(EQ).run {
    fix().eqv(other.fix())
  }
}

internal fun IO.Companion.genK() = object : GenK<IOPartialOf<Nothing>> {
  override fun <A> genK(gen: Gen<A>): Gen<Kind<IOPartialOf<Nothing>, A>> =
    Gen.oneOf(
      gen.map(IO.Companion::just),
      Gen.throwable().map { raiseException<A>(it) }
    )
}
