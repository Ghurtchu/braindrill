package simulator

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Random

object Simulator extends App:

  val actorSystem = "SimulatorSystem"
  given system: ActorSystem = ActorSystem(actorSystem, ConfigFactory.load("simulator.conf"))
  given ec: ExecutionContextExecutor = system.classicSystem.dispatcher

  val http = Http(system)

  val requestCount = AtomicInteger(0)
  val responseTimes = AtomicLong(0)
  val responseTimeDetails = mutable.ArrayBuffer.empty[Long]
  val errors = AtomicInteger(0)

  def stream(name: String, interval: FiniteDuration) = {

    // generate random code per 125 milliseconds
    val generateRandomCode: Source[Code, Cancellable] = Source.tick(0.seconds, interval, NotUsed)
      .map(_ => Code.random)

    // send http request to remote code execution engine
    val sendHttpRequest: Flow[Code, (Instant, Instant, String, Code), NotUsed] = Flow[Code]
      .mapAsync(100) { code =>
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = "http://localhost:8080/lang/python",
          entity = HttpEntity(ContentTypes.`application/json`, ByteString(code.value))
        )

        val now = Instant.now()
        val requestId = randomId()
        println(s"[$name]: sending Request($requestId, $code) at $now")

        http.singleRequest(request).map { response =>
          val end = Instant.now()
          val duration = ChronoUnit.MILLIS.between(now, end)
          response.discardEntityBytes()

          requestCount.incrementAndGet()
          responseTimes.addAndGet(duration)
          responseTimeDetails += duration

          (now, end, requestId, code)
        }.recover:
          case ex =>
            println(s"[$name] failed: ${ex.getMessage}")
            errors.incrementAndGet()
            (now, Instant.now(), requestId, code)
      }

    // display the http response time
    val displayResponseTime: Sink[(Instant, Instant, String, Code), Future[Done]] =
      Sink.foreach: (start, end, requestId, code) =>
        val duration = ChronoUnit.MILLIS.between(start, end)
        println(s"[$name]: received response for Request($requestId, $code) in $duration millis at: $end")

    // join the stream
    generateRandomCode
      .via(sendHttpRequest)
      .toMat(displayResponseTime)(Keep.right)
  }

  // run the stream
  stream("simulator", 125.millis)
    .run()

  system.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds): () =>
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

  private def randInterval(): FiniteDuration =
    Random
      .shuffle(List.iterate(300.millis, 10)(_ + 25.millis))
      .head

  private def randomId(): String =
    java.util.UUID.randomUUID()
      .toString
      .replace("-", "")
      .substring(1, 10)

  private def percentile(data: ArrayBuffer[Long], p: Double): Long =
    if data.isEmpty then 0
    else
      val sortedData = data.sorted
      val k = (sortedData.length * (p / 100.0)).ceil.toInt - 1

      sortedData(k)

  enum Code(val value: String):
    case MemoryIntensive extends Code(Python.MemoryIntensive)
    case CPUIntensive    extends Code(Python.CPUIntensive)
    case Medium          extends Code(Python.Medium)
    case Simple          extends Code(Python.Simple)
    case Instant         extends Code(Python.Instant)

  object Code:
    def random: Code = Random.shuffle(Code.values).head

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
        |for i in range(1, 100):
        |    print("number: " + str(i))
        |""".stripMargin

    val Instant = "print('hello world')"

