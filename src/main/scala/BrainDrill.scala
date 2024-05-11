import java.io.{BufferedReader, IOException}
import scala.annotation.tailrec
import scala.util.*
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object BrainDrill:

  def execute: Future[(String, String, Int)] =

    val cmd = Array(
      "docker",
      "run",
      "--rm",
      "-v",
      s"${System.getProperty("user.dir")}:/app",
      "-w",
      "/app",
      "python:3",
      "python",
      "Python.py",
    )

    for
      process <- startProcess(cmd)
      (success, error) = read(process.inputReader) -> read(process.errorReader)
      ((success, error), exitCode) <- (success zip error) zip Future(process.waitFor)
    yield (success, error, exitCode)

  private def startProcess(commands: Array[String]) =
    Future(Runtime.getRuntime.exec(commands))

  private def read(reader: BufferedReader) =
    Future:
      Using.resource(reader): reader =>

        @tailrec
        def loop(accumulated: String = ""): String =
          readLine(reader) match
            case Some(line) => loop(s"$accumulated$line\n")
            case None => accumulated

        loop()

  private def readLine(reader: BufferedReader) =
    Try(reader.readLine)
      .toOption
      .filter(line => line != null && line.nonEmpty)