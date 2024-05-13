package serialization

// just a marker trait to tell Apache Pekko to serialize messages into CBOR using Jackson for sending over the network
trait CborSerializable

