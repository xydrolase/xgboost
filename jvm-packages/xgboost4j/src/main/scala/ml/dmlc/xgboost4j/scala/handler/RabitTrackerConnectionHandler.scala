/*
 Copyright (c) 2014 by Contributors

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package ml.dmlc.xgboost4j.scala.handler

import java.nio.{ByteBuffer, ByteOrder}

import akka.io.Tcp
import akka.actor._
import akka.util.ByteString
import ml.dmlc.xgboost4j.scala.util.{AssignedRank, RabitTrackerHelpers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

object RabitTrackerConnectionHandler {
  val MAGIC_NUMBER = 0xff99

  // finite states
  sealed trait State
  case object AwaitingHandshake extends State
  case object AwaitingCommand extends State
  case object BuildingLinkMap extends State
  case object AwaitingErrorCount extends State
  case object AwaitingPortNumber extends State
  case object SetupComplete extends State

  sealed trait DataField
  case object IntField extends DataField
  // an integer preceding the actual string
  case object StringField extends DataField
  case object IntSeqField extends DataField

  object DataStruct {
    def apply(): DataStruct = DataStruct(Seq.empty[DataField], 0)
  }

  case class DataStruct(fields: Seq[DataField], counter: Int) {
    /**
      * Validate whether the provided buffer is complete (i.e., contains
      * all data fields specified for this DataStruct.
      * @param buf
      */
    def verify(buf: ByteBuffer): Boolean = {
      if (fields.isEmpty) return true

      val dupBuf = buf.duplicate().order(ByteOrder.nativeOrder())
      dupBuf.flip()

      Try(fields.foldLeft(true) {
        case (complete, field) =>
          val remBytes = dupBuf.remaining()
          complete && (remBytes > 0) && (remBytes >= (field match {
            case IntField =>
              dupBuf.position(dupBuf.position() + 4)
              4
            case StringField =>
              val strLen = dupBuf.getInt
              dupBuf.position(dupBuf.position() + strLen)
              4 + strLen
            case IntSeqField =>
              val seqLen = dupBuf.getInt
              dupBuf.position(dupBuf.position() + seqLen * 4)
              4 + seqLen * 4
          }))
      }).getOrElse(false)
    }

    def increment(): DataStruct = DataStruct(fields, counter + 1)
    def decrement(): DataStruct = DataStruct(fields, counter - 1)
  }

  val StructNodes = DataStruct(List(IntSeqField), 0)
  val StructTrackerCommand = DataStruct(List(
    IntField, IntField, StringField, StringField
  ), 0)

  sealed trait TrackerCommand {
    def rank: Int
    def worldSize: Int
    def jobId: String
  }

  // packaged worker commands
  case class WorkerStart(rank: Int, worldSize: Int, jobId: String) extends TrackerCommand
  case class WorkerShutdown(rank: Int, worldSize: Int, jobId: String) extends TrackerCommand
  case class WorkerRecover(rank: Int, worldSize: Int, jobId: String) extends TrackerCommand
  case class WorkerTrackerPrint(rank: Int, worldSize: Int, jobId: String, msg: String)
    extends TrackerCommand

  // request host and port information from peer actors
  case object RequestHostPort
  // response to the above request
  case class DivulgedHostPort(rank: Int, host: String, port: Int)
  case class AcknowledgeAcceptance(peers: Map[Int, ActorRef], numBad: Int)
  case class ReduceWaitCount(count: Int)

  case class DropFromWaitingList(rank: Int)
  case class WorkerStarted(host: String, rank: Int, awaitingAcceptance: Int)
  // Request, from the tracker, the set of nodes to connect.
  case class RequestAwaitConnWorkers(rank: Int, toConnectSet: Set[Int])
  case class AwaitingConnections(workers: Map[Int, ActorRef], numBad: Int)

  def props(host: String, worldSize: Int, tracker: ActorRef, connection: ActorRef): Props = {
    Props(new RabitTrackerConnectionHandler(host, worldSize, tracker, connection))
  }
}

/**
  * Actor to handle socket communication from worker node.
  * To handle fragmentation in received data, this class acts like a FSM
  * (finite-state machine) to keep track of the internal states.
  *
  * @param host IP address of the remote worker
  * @param worldSize number of total workers
  * @param tracker the RabitTrackerHandler actor reference
  */
class RabitTrackerConnectionHandler(host: String, worldSize: Int, tracker: ActorRef,
                                    connection: ActorRef)
  extends FSM[RabitTrackerConnectionHandler.State, RabitTrackerConnectionHandler.DataStruct]
    with ActorLogging with Stash {

  import RabitTrackerConnectionHandler._
  import RabitTrackerHelpers._

  context.watch(tracker)

  private[this] var rank: Int = 0
  private[this] var port: Int = 0

  // indicate if the connection is transient (like "print" or "shutdown")
  private[this] var transient: Boolean = false
  private[this] var peerClosed: Boolean = false

  // number of workers pending acceptance of current worker
  private[this] var awaitingAcceptance: Int = 0
  private[this] var neighboringWorkers = Set.empty[Int]

  // TODO: use a single memory allocation to host all buffers,
  // including the transient ones for writing.
  private[this] val readBuffer = ByteBuffer.allocate(4096)
    .order(ByteOrder.nativeOrder())
  // in case the received message is longer than needed,
  // stash the spilled over part in this buffer, and send
  // to self when transition occurs.
  private[this] val spillOverBuffer = ByteBuffer.allocate(4096)
    .order(ByteOrder.nativeOrder())
  // when setup is complete, need to notify peer handlers
  // to reduce the awaiting-connection counter.
  private[this] var pendingAcknowledgement: Option[AcknowledgeAcceptance] = None

  private def resetBuffers(): Unit = {
    readBuffer.clear()
    if (spillOverBuffer.position() > 0) {
      spillOverBuffer.flip()
      self ! Tcp.Received(ByteString.fromByteBuffer(spillOverBuffer))
      spillOverBuffer.clear()
    }
  }

  private def stashSpillOver(buf: ByteBuffer): Unit = {
    if (buf.remaining() > 0) spillOverBuffer.put(buf)
  }

  def decodeCommand(buffer: ByteBuffer): TrackerCommand = {
    val rank = buffer.getInt()
    val worldSize = buffer.getInt()
    val jobId = buffer.getString()

    val command = buffer.getString()
    command match {
      case "start" => WorkerStart(rank, worldSize, jobId)
      case "shutdown" =>
        transient = true
        WorkerShutdown(rank, worldSize, jobId)
      case "recover" =>
        require(rank >= 0, "Invalid rank for recovering worker.")
        WorkerRecover(rank, worldSize, jobId)
      case "print" =>
        transient = true
        WorkerTrackerPrint(rank, worldSize, jobId, buffer.getString())
    }
  }

  startWith(AwaitingHandshake, DataStruct())

  when(AwaitingHandshake) {
    case Event(Tcp.Received(magic), _) =>
      assert(magic.length == 4)
      val purportedMagic = magic.asNativeOrderByteBuffer.getInt
      assert(purportedMagic == MAGIC_NUMBER, s"invalid magic number $purportedMagic from $host")

      // echo back the magic number
      connection ! Tcp.Write(magic)
      goto(AwaitingCommand) using StructTrackerCommand
  }

  when(AwaitingCommand) {
    case Event(Tcp.Received(bytes), validator) =>
      bytes.asByteBuffers.foreach { buf => readBuffer.put(buf) }

      if (validator.verify(readBuffer)) {
        readBuffer.flip()
        tracker ! decodeCommand(readBuffer)
        stashSpillOver(readBuffer)
      }

      stay
    // when rank for a worker is assigned, send encoded rank information
    // back to worker over Tcp socket.
    case Event(AssignedRank(assignedRank, neighbors, ring, parent), _) =>
      log.debug(s"Assigned rank [$assignedRank] for $host, T: $neighbors, R: $ring, P: $parent")

      rank = assignedRank
      val buffer = ByteBuffer.allocate(4 * (neighbors.length + 6))
        .order(ByteOrder.nativeOrder())
      buffer.putInt(assignedRank).putInt(parent).putInt(worldSize).putInt(neighbors.length)
      // neighbors in tree structure
      neighbors.foreach { n => buffer.putInt(n) }
      // ranks from the ring
      val ringRanks = List(
        // ringPrev
        if (ring._1 != -1 && ring._1 != rank) ring._1 else -1,
        // ringNext
        if (ring._2 != -1 && ring._2 != rank) ring._2 else -1
      )
      ringRanks.foreach { r => buffer.putInt(r) }

      // update the set of all linked workers to current worker.
      neighboringWorkers = neighbors.toSet ++ ringRanks.filterNot(_ == -1).toSet

      buffer.flip()
      connection ! Tcp.Write(ByteString.fromByteBuffer(buffer))
      // to prevent reading before state transition
      connection ! Tcp.SuspendReading
      goto(BuildingLinkMap) using StructNodes
  }

  when(BuildingLinkMap) {
    case Event(Tcp.Received(bytes), validator) =>
      bytes.asByteBuffers.foreach { buf =>
        readBuffer.put(buf)
      }

      if (validator.verify(readBuffer)) {
        readBuffer.flip()
        // for a freshly started worker, numConnected should be 0.
        val numConnected = readBuffer.getInt()
        val toConnectSet = neighboringWorkers.diff(
          (0 until numConnected).map { index => readBuffer.getInt() }.toSet)

        // check which workers are currently awaiting connections
        tracker ! RequestAwaitConnWorkers(rank, toConnectSet)
      }
      stay

    // got a Future from the tracker (resolver) about workers that are
    // currently awaiting connections (particularly from this node.)
    case Event(future: Future[_], _) =>
      // blocks execution until all dependencies for current worker is resolved.
      Await.result(future, 1 minute).asInstanceOf[AwaitingConnections] match {
        // numNotReachable is the number of workers that currently
        // cannot be connected to (pending connection or setup). Instead, this worker will AWAIT
        // connections from those currently non-reachable nodes in the future.
        case AwaitingConnections(waitConnNodes, numNotReachable) =>
          log.debug(s"Rank $rank needs to connect to: $waitConnNodes, # bad: $numNotReachable")
          val buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
          buf.putInt(waitConnNodes.size).putInt(numNotReachable)
          buf.flip()

          // cache this message until the final state (SetupComplete)
          pendingAcknowledgement = Some(AcknowledgeAcceptance(
            waitConnNodes, numNotReachable))

          connection ! Tcp.Write(ByteString.fromByteBuffer(buf))
          if (waitConnNodes.isEmpty) {
            connection ! Tcp.SuspendReading
            goto(AwaitingErrorCount)
          }
          else {
            waitConnNodes.foreach { case (peerRank, peerRef) =>
              peerRef ! RequestHostPort
            }

            // a countdown for DivulgedHostPort messages.
            stay using DataStruct(Seq.empty[DataField], waitConnNodes.size - 1)
          }
      }

    case Event(DivulgedHostPort(peerRank, peerHost, peerPort), data) =>
      val hostBytes = peerHost.getBytes()
      val buffer = ByteBuffer.allocate(4 * 3 + hostBytes.length)
        .order(ByteOrder.nativeOrder())
      buffer.putInt(peerHost.length).put(hostBytes)
        .putInt(peerPort).putInt(peerRank)

      buffer.flip()
      connection ! Tcp.Write(ByteString.fromByteBuffer(buffer))

      if (data.counter == 0) {
        // to prevent reading before state transition
        connection ! Tcp.SuspendReading
        goto(AwaitingErrorCount)
      }
      else {
        stay using data.decrement()
      }
  }

  when(AwaitingErrorCount) {
    case Event(Tcp.Received(numErrors), _) =>
      val buf = numErrors.asNativeOrderByteBuffer

      buf.getInt match {
        case 0 =>
          stashSpillOver(buf)
          goto(AwaitingPortNumber)
        case _ =>
          stashSpillOver(buf)
          goto(BuildingLinkMap) using StructNodes

      }
  }

  when(AwaitingPortNumber) {
    case Event(Tcp.Received(assignedPort), _) =>
      assert(assignedPort.length == 4)
      port = assignedPort.asNativeOrderByteBuffer.getInt
      log.debug(s"Rank $rank listening @ $host:$port")
      // wait until the worker closes connection.
      if (peerClosed) goto(SetupComplete) else stay

    case Event(Tcp.PeerClosed, _) =>
      peerClosed = true
      if (port == 0) stay else goto(SetupComplete)
  }

  when(SetupComplete) {
    case Event(ReduceWaitCount(count: Int), _) =>
      awaitingAcceptance -= count
      // check peerClosed to avoid prematurely stopping this actor (which sends RST to worker)
      if (awaitingAcceptance == 0 && peerClosed) {
        tracker ! DropFromWaitingList(rank)
        // no long needed.
        context.stop(self)
      }
      stay

    case Event(AcknowledgeAcceptance(peers, numBad), _) =>
      awaitingAcceptance = numBad
      tracker ! WorkerStarted(host, rank, awaitingAcceptance)
      peers.values.foreach { peer =>
        peer ! ReduceWaitCount(1)
      }

      if (awaitingAcceptance == 0 && peerClosed) self ! PoisonPill

      stay

    // can only divulge the complete host and port information
    // when this worker is declared fully connected (otherwise
    // port information is still missing.)
    case Event(RequestHostPort, _) =>
      sender() ! DivulgedHostPort(rank, host, port)
      stay
  }

  onTransition {
    // reset buffer when state transitions as data becomes stale
    case _ -> SetupComplete =>
      connection ! Tcp.ResumeReading
      resetBuffers()
      if (pendingAcknowledgement.isDefined) {
        self ! pendingAcknowledgement.get
      }
    case _ =>
      connection ! Tcp.ResumeReading
      resetBuffers()
  }

  // default message handler
  whenUnhandled {
    case Event(Tcp.PeerClosed, _) =>
      peerClosed = true
      if (transient) context.stop(self)
      stay
  }
}
