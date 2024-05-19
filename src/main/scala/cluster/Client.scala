package cluster

import cluster.Service.TaskResult
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import serialization.CborSerializable

object Client {

  sealed trait In
  
  object In:
    case class AssignTask(code: String, language: String, replyTo: ActorRef[Service.TaskResult]) extends In with CborSerializable
    
    case class ServiceResponse(result: Service.TaskResult) extends In with CborSerializable

  def apply(service: ActorRef[Service.In.AssignTask]): Behavior[In] =
    Behaviors.setup { ctx =>

      val responseAdapter: ActorRef[Service.TaskResult] = ctx.messageAdapter(In.ServiceResponse.apply)

      def behavior(requester: Option[ActorRef[Service.TaskResult]] = None): Behavior[In] = {
        Behaviors.receiveMessage {
          case In.AssignTask(code, language, replyTo: ActorRef[Service.TaskResult]) =>
            service ! Service.In.AssignTask(code, language, responseAdapter)

            behavior(Some(replyTo))

          case In.ServiceResponse(result) =>
            ctx.log.info("Service result: {}", result)
            requester.foreach(_ ! result)

            Behaviors.same
        }
      }

      behavior(None)
    }

}
