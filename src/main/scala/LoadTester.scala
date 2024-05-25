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
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object LoadTester extends App {

  enum Code(val value: String):
    case MemoryIntensive extends Code(Python.MemoryIntensive)
    case CPUIntensive    extends Code(Python.CPUIntensive)
    case Medium          extends Code(Python.Medium)
    case Simple          extends Code(Python.Simple)
    case Instant         extends Code(Python.Instant)

  object Code:
    def random: Code = Code.values.apply(Random.nextInt(Code.values.length))

  object Python:
    val MemoryIntensive =
      """
        |def memory_intensive_task(size_mb):
        |    # Create a list of integers to consume memory
        |    data = [0] * (size_mb * 1024 * 1024)  # Each element takes 8 bytes on a 64-bit system
        |    return data
        |print(memory_intensive_task(10))  # Allocate 10 MB of memory
        |""".stripMargin

    val CPUIntensive =
      """
        |def cpu_intensive_task(n):
        |    result = 0
        |    for i in range(n):
        |        result += i * i
        |    return result
        |print(cpu_intensive_task(50))
        |""".stripMargin

    val Medium =
      """import random
        |
        |# Initialize the stop variable to False
        |stop = False
        |
        |while not stop:
        |    # Generate a random number between 1 and 100
        |    random_number = random.randint(1, 1000)
        |    print(f"Generated number: {random_number}")
        |
        |    # Check if the generated number is greater than 80
        |    if random_number == 1000:
        |        stop = True
        |
        |print("Found a number greater than 80. Exiting loop.")
        |""".stripMargin

    val Simple =
      """
        |for i in range(1, 10):
        |    print("number: " + str(i))
        |""".stripMargin

    val Instant = "print('hello world')"

  given system: ActorSystem = ActorSystem("LoadTester")
  given ec: ExecutionContextExecutor = system.classicSystem.dispatcher

  val http = Http(system)

  val requestCount = new AtomicInteger(0)
  val responseTimes = new AtomicLong(0)
  val responseTimeDetails = mutable.ArrayBuffer[Long]()
  val errors = new AtomicInteger(0)

  def randInterval() =
    Random.shuffle {
      List
        .iterate(100, 18)(_ + 50)
        .map(_.millis)
    }.head

  def randId(): String =
    java.util.UUID.randomUUID()
      .toString
      .replace("-", "")
      .substring(1, 10)

  def stream(name: String, interval: FiniteDuration) = {

    println(s"[$name] generating random code per $interval")

    val generateRandomCode: Source[Code, Cancellable] = Source.tick(0.seconds, interval, NotUsed)
      .map(_ => Code.random)

    val sendHttpRequest: Flow[Code, (Instant, Instant, String, Code), NotUsed] = Flow[Code]
      .mapAsync(100) { code =>
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = "http://localhost:8080/lang/python",
          entity = HttpEntity(ContentTypes.`application/json`, ByteString(code.value))
        )

        val now = Instant.now()
        val requestId = randId()
        println(s"[$name]: sending Request($requestId, $code) at $now")

        http.singleRequest(request).map { response =>
          val end = Instant.now()
          response.discardEntityBytes()

          requestCount.incrementAndGet()
          val duration = ChronoUnit.MILLIS.between(now, end)
          responseTimes.addAndGet(duration)
          responseTimeDetails += duration

          (now, end, requestId, code)
        }.recover {
          case ex =>
            println(ex)
            errors.incrementAndGet()
            (now, Instant.now(), requestId, code)
        }
      }

    val showResponseTime: Sink[(Instant, Instant, String, Code), Future[Done]] = Sink.foreach { case (start, end, requestId, code) =>
      val duration = ChronoUnit.MILLIS.between(start, end)
      println(s"[$name]: received response for Request($requestId, $code) in $duration millis at: $end, now: ${Instant.now()}")
    }

    generateRandomCode
      .via(sendHttpRequest)
      .toMat(showResponseTime)(Keep.right)
  }


  stream(s"stream", 100.millis)
      .run()

  system.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds) { () =>
    val count = requestCount.getAndSet(0)
    val totalResponseTime = responseTimes.getAndSet(0)
    val averageResponseTime = if (count > 0) totalResponseTime / count else 0
    val errorCount = errors.getAndSet(0)
    val p50 = percentile(responseTimeDetails, 50)
    val p90 = percentile(responseTimeDetails, 90)
    val p99 = percentile(responseTimeDetails, 99)

    println("-" * 50)
    println(s"Requests in last minute: $count")
    println(s"Average response time: $averageResponseTime ms")
    println(s"Error count: $errorCount")
    println(s"Response time percentiles: p50=$p50 ms, p90=$p90 ms, p99=$p99 ms")
    println("-" * 50)

    responseTimeDetails.clear()
  }

  def percentile(data: ArrayBuffer[Long], p: Double): Long = {
    if (data.isEmpty) return 0
    val sortedData = data.sorted
    val k = (sortedData.length * (p / 100.0)).ceil.toInt - 1
    sortedData(k)
  }
}
