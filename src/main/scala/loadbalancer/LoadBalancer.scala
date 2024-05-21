package loadbalancer

import WorkDelegator.TaskResult
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import serialization.CborSerializable

// sends AssignTask message to one of the available/free remote WorkDistributor actors on some node
object LoadBalancer {

  sealed trait In
  
  object In:
    final case class AssignTask(code: String, language: String, replyTo: ActorRef[WorkDelegator.TaskResult]) extends In with CborSerializable
    final case class Response(result: WorkDelegator.TaskResult) extends In with CborSerializable

  def apply(workDelegator: ActorRef[WorkDelegator.In.AssignTask]): Behavior[In] =
    Behaviors.setup { ctx =>
      val responseAdapter: ActorRef[WorkDelegator.TaskResult] = ctx.messageAdapter(In.Response.apply)

      def behavior(requester: Option[ActorRef[WorkDelegator.TaskResult]] = None): Behavior[In] = {
        Behaviors.receiveMessage {
          case In.AssignTask(code, language, replyTo: ActorRef[WorkDelegator.TaskResult]) =>
            workDelegator ! WorkDelegator.In.AssignTask(code, language, responseAdapter)

            behavior(Some(replyTo))

          case In.Response(result) =>
            ctx.log.info("Service result: {}", result)
            requester.foreach(_ ! result)

            Behaviors.same
        }
      }

      behavior(None)
    }

}
