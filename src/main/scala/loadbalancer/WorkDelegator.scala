package loadbalancer

import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import serialization.CborSerializable
import workers.Worker

// sends AssignTask to one of the available/free worker actors on the local node
object WorkDelegator {

  val Key = ServiceKey[WorkDelegator.In.AssignTask]("WorkDistributor.In.AssignTask")

  sealed trait In

  object In:
    case class AssignTask(code: String, language: String, replyTo: ActorRef[WorkDelegator.TaskResult]) extends In with CborSerializable

  final case class TaskResult(output: String) extends CborSerializable

  def apply(workers: ActorRef[Worker.StartExecution]): Behavior[WorkDelegator.In] = {
    Behaviors.setup { ctx =>
      // if all workers would crash/stop we want to stop as well
      ctx.watch(workers)

      Behaviors.receiveMessage:
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
