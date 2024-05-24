import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.util.ByteString

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.util.Random

object LoadTester extends App {

  enum Code(val value: String):
    case MemoryIntensive extends Code(Python.MemoryIntensive)
    case CPUIntensive extends Code(Python.CPUIntensive)
    case Abusive extends Code(Python.Abusive)
    case Simple extends Code(Python.Simple)

  object Code:
    def random: Code = Code.values.apply(Random.nextInt(Code.values.length))

  object Python:
    val MemoryIntensive =
      """
        |def memory_intensive_task(size_mb):
        |    # Create a list of integers to consume memory
        |    data = [0] * (size_mb * 1024 * 1024 // 8)  # Each element takes 8 bytes on a 64-bit system
        |    return data
        |
        |# Example usage
        |result = memory_intensive_task(100)  # Allocate 100 MB of memory
        |print("Memory-intensive task completed.")
        |
        |""".stripMargin

    val CPUIntensive =
      """
        |def cpu_intensive_task(n):
        |    result = 0
        |    for i in range(n):
        |        result += i * i
        |    return result
        |
        |# Example usage
        |result = cpu_intensive_task(1000000)
        |print(result)
        |
        |""".stripMargin

    val Abusive =
      """
        |while True:
        |    print("infinite loop")
        |""".stripMargin

    val Simple =
      """
        |def hello_world():
        |    print("hello, world!")
        |
        |hello_world()
        |""".stripMargin

  given system: ActorSystem = ActorSystem("LoadTester")
  given ec: ExecutionContextExecutor = system.classicSystem.dispatcher

  val http = Http(system)

  val source: Source[(Instant, Code), Cancellable] = Source.tick(0.seconds, 0.1.seconds, NotUsed)
    .map(_ => (Instant.now(), Code.random))

  val flow: Flow[(Instant, Code), (Instant, Instant, Code), NotUsed] = Flow[(Instant, Code)]
    .mapAsyncUnordered(10) { case (startTime, code) =>
      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = "http://localhost:8080/lang/python",
        entity = HttpEntity(ContentTypes.`application/json`, ByteString(code.value))
      )

      val responseFuture: Future[HttpResponse] = http.singleRequest(request)
      responseFuture.flatMap { response =>
        response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body =>
          val responseBody = body.utf8String
          println(s"output $responseBody")
          response.discardEntityBytes()
          (startTime, Instant.now(), code)
        }
      }
    }

  val sink: Sink[(Instant, Instant, Code), Future[Done]] = Sink.foreach { case (start, end, code) =>
    val duration = ChronoUnit.MILLIS.between(start, end)
    println(s"$code execution took $duration milliseconds")
  }

  val runnableGraph = source.via(flow).toMat(sink)(Keep.right)

  runnableGraph.run()
}
