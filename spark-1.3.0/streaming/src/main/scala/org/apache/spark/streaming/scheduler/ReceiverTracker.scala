/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.scheduler


import scala.collection.mutable.{HashMap, SynchronizedMap}
import scala.language.existentials

import akka.actor._

import org.apache.spark.{Logging, SerializableWritable, SparkEnv, SparkException}
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.receiver.{CleanupOldBlocks, Receiver, ReceiverSupervisorImpl, StopReceiver}

/**
 * Messages used by the NetworkReceiver and the ReceiverTracker to communicate
 * with each other.
 */
private[streaming] sealed trait ReceiverTrackerMessage
private[streaming] case class RegisterReceiver(
    streamId: Int,
    typ: String,
    host: String,
    receiverActor: ActorRef
  ) extends ReceiverTrackerMessage
private[streaming] case class AddBlock(receivedBlockInfo: ReceivedBlockInfo)
  extends ReceiverTrackerMessage
private[streaming] case class ReportError(streamId: Int, message: String, error: String)
private[streaming] case class DeregisterReceiver(streamId: Int, msg: String, error: String)
  extends ReceiverTrackerMessage

/**
 * This class manages the execution of the receivers of ReceiverInputDStreams. Instance of
 * this class must be created after all input streams have been added and StreamingContext.start()
 * has been called because it needs the final set of input streams at the time of instantiation.
 *
 * @param skipReceiverLaunch Do not launch the receiver. This is useful for testing.
 */
private[streaming]
class ReceiverTracker(ssc: StreamingContext, skipReceiverLaunch: Boolean = false) extends Logging {

  // 这个receiverInputStream，就是从StreamingContext的graph中，取出的
  // DStreamGraph里面取出来的
  // 就是说，每次调用StreamingContext创建一个输入DStream时，都会放入DStreamGraph的ReceiverInputStreams
  private val receiverInputStreams = ssc.graph.getReceiverInputStreams()
  private val receiverInputStreamIds = receiverInputStreams.map { _.id }
  private val receiverExecutor = new ReceiverLauncher()
  private val receiverInfo = new HashMap[Int, ReceiverInfo] with SynchronizedMap[Int, ReceiverInfo]
  private val receivedBlockTracker = new ReceivedBlockTracker(
    ssc.sparkContext.conf,
    ssc.sparkContext.hadoopConfiguration,
    receiverInputStreamIds,
    ssc.scheduler.clock,
    Option(ssc.checkpointDir)
  )
  private val listenerBus = ssc.scheduler.listenerBus

  // actor is created when generator starts.
  // This not being null means the tracker has been started and not stopped
  private var actor: ActorRef = null

  /** Start the actor and receiver execution thread. */
  def start() = synchronized {
    if (actor != null) {
      throw new SparkException("ReceiverTracker already started")
    }

    if (!receiverInputStreams.isEmpty) {
      actor = ssc.env.actorSystem.actorOf(Props(new ReceiverTrackerActor),
        "ReceiverTracker")
      // 这个start()方法中，主要就是调用了其内部的ReceiverLauncher的start()方法
      // 说白了，这个ReceiverTracker的主要作用，就是启动Receiver
      if (!skipReceiverLaunch) receiverExecutor.start()
      logInfo("ReceiverTracker started")
    }
  }

  /** Stop the receiver execution thread. */
  def stop(graceful: Boolean) = synchronized {
    if (!receiverInputStreams.isEmpty && actor != null) {
      // First, stop the receivers
      if (!skipReceiverLaunch) receiverExecutor.stop(graceful)

      // Finally, stop the actor
      ssc.env.actorSystem.stop(actor)
      actor = null
      receivedBlockTracker.stop()
      logInfo("ReceiverTracker stopped")
    }
  }

  /** Allocate all unallocated blocks to the given batch. */
  def allocateBlocksToBatch(batchTime: Time): Unit = {
    if (receiverInputStreams.nonEmpty) {
      receivedBlockTracker.allocateBlocksToBatch(batchTime)
    }
  }

  /** Get the blocks for the given batch and all input streams. */
  def getBlocksOfBatch(batchTime: Time): Map[Int, Seq[ReceivedBlockInfo]] = {
    receivedBlockTracker.getBlocksOfBatch(batchTime)
  }

  /** Get the blocks allocated to the given batch and stream. */
  def getBlocksOfBatchAndStream(batchTime: Time, streamId: Int): Seq[ReceivedBlockInfo] = {
    synchronized {
      receivedBlockTracker.getBlocksOfBatchAndStream(batchTime, streamId)
    }
  }

  /**
   * Clean up the data and metadata of blocks and batches that are strictly
   * older than the threshold time. Note that this does not
   */
  def cleanupOldBlocksAndBatches(cleanupThreshTime: Time) {
    // Clean up old block and batch metadata
    receivedBlockTracker.cleanupOldBatches(cleanupThreshTime, waitForCompletion = false)

    // Signal the receivers to delete old block data
    if (ssc.conf.getBoolean("spark.streaming.receiver.writeAheadLog.enable", false)) {
      logInfo(s"Cleanup old received batch data: $cleanupThreshTime")
      receiverInfo.values.flatMap { info => Option(info.actor) }
        .foreach { _ ! CleanupOldBlocks(cleanupThreshTime) }
    }
  }

  /** Register a receiver */
  private def registerReceiver(
      streamId: Int,
      typ: String,
      host: String,
      receiverActor: ActorRef,
      sender: ActorRef
    ) {
    if (!receiverInputStreamIds.contains(streamId)) {
      throw new SparkException("Register received for unexpected id " + streamId)
    }
    receiverInfo(streamId) = ReceiverInfo(
      streamId, s"${typ}-${streamId}", receiverActor, true, host)
    listenerBus.post(StreamingListenerReceiverStarted(receiverInfo(streamId)))
    logInfo("Registered receiver for stream " + streamId + " from " + sender.path.address)
  }

  /** Deregister a receiver */
  private def deregisterReceiver(streamId: Int, message: String, error: String) {
    val newReceiverInfo = receiverInfo.get(streamId) match {
      case Some(oldInfo) =>
        oldInfo.copy(actor = null, active = false, lastErrorMessage = message, lastError = error)
      case None =>
        logWarning("No prior receiver info")
        ReceiverInfo(streamId, "", null, false, "", lastErrorMessage = message, lastError = error)
    }
    receiverInfo -= streamId
    listenerBus.post(StreamingListenerReceiverStopped(newReceiverInfo))
    val messageWithError = if (error != null && !error.isEmpty) {
      s"$message - $error"
    } else {
      s"$message"
    }
    logError(s"Deregistered receiver for stream $streamId: $messageWithError")
  }

  /** Add new blocks for the given stream */
  private def addBlock(receivedBlockInfo: ReceivedBlockInfo): Boolean = {
    receivedBlockTracker.addBlock(receivedBlockInfo)
  }

  /** Report error sent by a receiver */
  private def reportError(streamId: Int, message: String, error: String) {
    val newReceiverInfo = receiverInfo.get(streamId) match {
      case Some(oldInfo) =>
        oldInfo.copy(lastErrorMessage = message, lastError = error)
      case None =>
        logWarning("No prior receiver info")
        ReceiverInfo(streamId, "", null, false, "", lastErrorMessage = message, lastError = error)
    }
    receiverInfo(streamId) = newReceiverInfo
    listenerBus.post(StreamingListenerReceiverError(receiverInfo(streamId)))
    val messageWithError = if (error != null && !error.isEmpty) {
      s"$message - $error"
    } else {
      s"$message"
    }
    logWarning(s"Error reported by receiver for stream $streamId: $messageWithError")
  }

  /** Check if any blocks are left to be processed */
  def hasUnallocatedBlocks: Boolean = {
    receivedBlockTracker.hasUnallocatedReceivedBlocks
  }

  /** Actor to receive messages from the receivers. */
  private class ReceiverTrackerActor extends Actor {
    def receive = {
      case RegisterReceiver(streamId, typ, host, receiverActor) =>
        registerReceiver(streamId, typ, host, receiverActor, sender)
        sender ! true
      case AddBlock(receivedBlockInfo) =>
        sender ! addBlock(receivedBlockInfo)
      case ReportError(streamId, message, error) =>
        reportError(streamId, message, error)
      case DeregisterReceiver(streamId, message, error) =>
        deregisterReceiver(streamId, message, error)
        sender ! true
    }
  }

  /** This thread class runs all the receivers on the cluster.  */
  class ReceiverLauncher {
    @transient val env = ssc.env
    @volatile @transient private var running = false
    
    // thread
    @transient val thread  = new Thread() {
      override def run() {
        try {
          SparkEnv.set(env)
          // 这里，就是开始启动所有DStream对应的receivers
          startReceivers()
        } catch {
          case ie: InterruptedException => logInfo("ReceiverLauncher interrupted")
        }
      }
    }

    /**
     * ReceiverLauncher的start()方法，其实启动了内部的一个线程
     * 所以，相当于是使用异步的方式来启动Receiver
     */
    def start() {
      thread.start()
    }

    def stop(graceful: Boolean) {
      // Send the stop signal to all the receivers
      stopReceivers()

      // Wait for the Spark job that runs the receivers to be over
      // That is, for the receivers to quit gracefully.
      thread.join(10000)

      if (graceful) {
        val pollTime = 100
        def done = { receiverInfo.isEmpty && !running }
        logInfo("Waiting for receiver job to terminate gracefully")
        while(!done) {
          Thread.sleep(pollTime)
        }
        logInfo("Waited for receiver job to terminate gracefully")
      }

      // Check if all the receivers have been deregistered or not
      if (!receiverInfo.isEmpty) {
        logWarning("Not all of the receivers have deregistered, " + receiverInfo)
      } else {
        logInfo("All of the receivers have deregistered successfully")
      }
    }

    /**
     * Get the receivers from the ReceiverInputDStreams, distributes them to the
     * worker nodes as a parallel collection, and runs them.
     */
    /**
     * 一直到这里，ReceiverTracker的startReceivers()，都是在Driver上执行的
     * ok，这个没有问题
     */
    private def startReceivers() {
      // 这里啊，就很重要了
      // 将程序中创建的所有输入DStream，调用其getReceiver()方法，拿到一个receivers集合
      val receivers = receiverInputStreams.map(nis => {
        val rcvr = nis.getReceiver()
        rcvr.setReceiverId(nis.id)
        rcvr
      })

      // 然后会拿到这些receiver的一些最佳位置
      // Right now, we only honor preferences if all receivers have them
      val hasLocationPreferences = receivers.map(_.preferredLocation.isDefined).reduce(_ && _)

      // Create the parallel collection of receivers to distributed them on the worker nodes
      val tempRDD =
        if (hasLocationPreferences) {
          val receiversWithPreferences = receivers.map(r => (r, Seq(r.preferredLocation.get)))
          ssc.sc.makeRDD[Receiver[_]](receiversWithPreferences)
        } else {
          ssc.sc.makeRDD(receivers, receivers.size)
        }

      val checkpointDirOption = Option(ssc.checkpointDir)
      val serializableHadoopConf = new SerializableWritable(ssc.sparkContext.hadoopConfiguration)

      // Function to start the receiver on the worker node
      // 这里，其实定义了启动receiver的核心逻辑
      // 只是定义而已，所以，一直到后面，都是只是定义，不是在这里执行的，不是在driver执行的
      // 定义了一个函数，startReceiver
      // 这个函数的执行，以及往后的过程，都是在Executor上执行的
      // 这点，必须，必须，明了
      // Receiver的启动，绝对不是在Driver上的，是在executor上的
      val startReceiver = (iterator: Iterator[Receiver[_]]) => {
        if (!iterator.hasNext) {
          throw new SparkException(
            "Could not start receiver as object not found.")
        }
        val receiver = iterator.next()
        // 将每一个Receiver封装在ReceiverSupervisorImpl中，并调用其start()方法，启动
        val supervisor = new ReceiverSupervisorImpl(
          receiver, SparkEnv.get, serializableHadoopConf.value, checkpointDirOption)
        supervisor.start()
        supervisor.awaitTermination()
      }
      // Run the dummy Spark job to ensure that all slaves have registered.
      // This avoids all the receivers to be scheduled on the same node.
      if (!ssc.sparkContext.isLocal) {
        ssc.sparkContext.makeRDD(1 to 50, 50).map(x => (x, 1)).reduceByKey(_ + _, 20).collect()
      }

      // Distribute the receivers and start them
      // 这里，调用StreamingContext的SparkContext的runJob，其实会真正，将启动Receiver的函数
      // 分布到各个worker节点的executor上去执行
      logInfo("Starting " + receivers.length + " receivers")
      running = true
      ssc.sparkContext.runJob(tempRDD, ssc.sparkContext.clean(startReceiver))
      running = false
      logInfo("All of the receivers have been terminated")
    }

    /** Stops the receivers. */
    private def stopReceivers() {
      // Signal the receivers to stop
      receiverInfo.values.flatMap { info => Option(info.actor)}
                         .foreach { _ ! StopReceiver }
      logInfo("Sent stop signal to all " + receiverInfo.size + " receivers")
    }
  }
}
