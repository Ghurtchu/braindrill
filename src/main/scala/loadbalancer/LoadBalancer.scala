package loadbalancer

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import serialization.CborSerializable
import workers.Worker

// sends AssignTask message to one of the available/free remote WorkDistributor actors on some node
object LoadBalancer {

  sealed trait In
  
  object In:
    final case class AssignTask(code: String, language: String, replyTo: ActorRef[Worker.ExecutionResult]) extends In with CborSerializable
    final case class Response(result: Worker.ExecutionResult) extends In with CborSerializable

  def apply(workDelegator: ActorRef[WorkDelegator.In.DelegateWork]): Behavior[In] =
    Behaviors.setup { ctx =>
      val responseAdapter: ActorRef[Worker.ExecutionResult] = ctx.messageAdapter(In.Response.apply)

      def behavior(requester: Option[ActorRef[Worker.ExecutionResult]] = None): Behavior[In] = {
        Behaviors.receiveMessage {
          case In.AssignTask(code, language, replyTo: ActorRef[Worker.ExecutionResult]) =>
            workDelegator ! WorkDelegator.In.DelegateWork(code, language, responseAdapter)

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
