/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import akka.actor.typed.scaladsl.Actor._
import akka.util.Timeout

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PerformanceSpec extends TypedSpec(
  ConfigFactory.parseString(
    """
      # increase this if you do real benchmarking
      akka.actor.typed.PerformanceSpec.iterations=100000
      """)) {

  override def setTimeout = Timeout(20.seconds)

  case class Ping(x: Int, pong: ActorRef[Pong], report: ActorRef[Pong])
  case class Pong(x: Int, ping: ActorRef[Ping], report: ActorRef[Pong])

  def behavior(pairs: Int, pings: Int, count: Int, executor: String) =
    StepWise[Pong] { (ctx, startWith) ⇒
      startWith {

        val pinger = immutable[Ping] { (ctx, msg) ⇒
          if (msg.x == 0) {
            msg.report ! Pong(0, ctx.self, msg.report)
            same
          } else {
            msg.pong ! Pong(msg.x - 1, ctx.self, msg.report)
            same
          }
        } // FIXME .withDispatcher(executor)

        val ponger = immutable[Pong] { (ctx, msg) ⇒
          msg.ping ! Ping(msg.x, ctx.self, msg.report)
          same
        } // FIXME .withDispatcher(executor)

        val actors =
          for (i ← 1 to pairs)
            yield (ctx.spawn(pinger, s"pinger-$i"), ctx.spawn(ponger, s"ponger-$i"))

        val start = Deadline.now

        for {
          (ping, pong) ← actors
          _ ← 1 to pings
        } ping ! Ping(count, pong, ctx.self)

        start
      }.expectMultipleMessages(10.seconds, pairs * pings) { (msgs, start) ⇒
        val stop = Deadline.now

        val rate = 2L * count * pairs * pings / (stop - start).toMillis
        info(s"messaging rate was $rate/ms")
      }
    }

  val iterations = system.settings.config.getInt("akka.actor.typed.PerformanceSpec.iterations")

  "An immutable behaviour" must {
    "when warming up" in {
      sync(runTest("01")(behavior(1, 1, iterations, "dispatcher-1")))
    }

    "when using a single message on a single thread" in {
      sync(runTest("02")(behavior(1, 1, iterations, "dispatcher-1")))
    }

    "when using a 10 messages on a single thread" in {
      sync(runTest("03")(behavior(1, 10, iterations, "dispatcher-1")))
    }

    "when using a single message on two threads" in {
      sync(runTest("04")(behavior(1, 1, iterations, "dispatcher-2")))
    }

    "when using a 10 messages on two threads" in {
      sync(runTest("05")(behavior(1, 10, iterations, "dispatcher-2")))
    }

    "when using 4 pairs with a single message" in {
      sync(runTest("06")(behavior(4, 1, iterations, "dispatcher-8")))
    }

    "when using 4 pairs with 10 messages" in {
      sync(runTest("07")(behavior(4, 10, iterations, "dispatcher-8")))
    }

    "when using 8 pairs with a single message" in {
      sync(runTest("08")(behavior(8, 1, iterations, "dispatcher-8")))
    }

    "when using 8 pairs with 10 messages" in {
      sync(runTest("09")(behavior(8, 10, iterations, "dispatcher-8")))
    }

  }
}
