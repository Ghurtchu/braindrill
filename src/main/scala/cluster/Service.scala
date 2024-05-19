package cluster

import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import serialization.CborSerializable
import workers.Worker

object Service {

  val Key = ServiceKey[Service.In.AssignTask]("ServiceKey")

  sealed trait In


  object In:
    case class AssignTask(code: String, language: String, replyTo: ActorRef[Service.TaskResult]) extends In with CborSerializable

  final case class TaskResult(output: String) extends CborSerializable

  def apply(workers: ActorRef[Worker.StartExecution]): Behavior[Service.In] = {
    Behaviors.setup { ctx =>
      // if all workers would crash/stop we want to stop as well
      ctx.watch(workers)

      Behaviors.receiveMessage {
        case msg: In.AssignTask =>
          workers ! Worker.StartExecution(
            code = msg.code,
            language = msg.language,
            replyTo = msg.replyTo
          )
          Behaviors.same
      }

    }
  }

}
