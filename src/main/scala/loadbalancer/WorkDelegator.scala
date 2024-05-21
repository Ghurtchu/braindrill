package loadbalancer

import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import serialization.CborSerializable
import workers.Worker

// sends AssignTask to one of the available/free worker actors on the local node
object WorkDelegator {

  val Key = ServiceKey[WorkDelegator.In.DelegateWork]("WorkDistributor.In.AssignTask")

  sealed trait In

  object In:
    case class DelegateWork(code: String, language: String, replyTo: ActorRef[Worker.ExecutionResult]) extends In with CborSerializable

  def apply(workersPool: ActorRef[Worker.StartExecution]): Behavior[WorkDelegator.In] = {
    Behaviors.setup { ctx =>
      // if all workers would crash/stop we want to stop as well
      ctx.watch(workersPool)

      Behaviors.receiveMessage:
        case msg: In.DelegateWork =>
          workersPool ! Worker.StartExecution(
            code = msg.code,
            language = msg.language,
            replyTo = msg.replyTo
          )

          Behaviors.same

    }
  }

}
