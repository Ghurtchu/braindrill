package actors

/**
 * Marker trait to tell Apache Pekko to serialize messages into CBOR using Jackson for sending over the network
 * See application.conf where it is bound to a serializer.
 * For more details see the docs https://pekko.apache.org/docs/pekko/current/serialization-jackson.html
 */
trait CborSerializable

