package com.lnvortex.lnd

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import com.lnvortex.core.{api, NodeUri}
import com.lnvortex.core.api.OutputDetails
import grizzled.slf4j.Logging
import lnrpc.FundingShim.Shim.{PsbtShim => FundingPsbtShim}
import lnrpc.FundingTransitionMsg.Trigger._
import lnrpc.OpenStatusUpdate.Update.PsbtFund
import lnrpc._
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.LndUtils._
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.concurrent._

case class LndChannelOpener(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem)
    extends Logging {
  private val lnd: LightningClient = lndRpcClient.lnd
  implicit val executionContext: ExecutionContext = system.dispatcher

  def initPSBTChannelOpen(
      nodeUri: NodeUri,
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails] = {
    initPSBTChannelOpen(nodeUri.nodeId,
                        Some(nodeUri.socketAddress),
                        fundingAmount,
                        privateChannel)
  }

  /** Connects to the peer and starts the process of opening a channel
    * @param nodeId NodeId of who to open the channel to
    * @param peerAddrOpt Node's socket address, None if already connected
    * @param fundingAmount Size of channel
    * @param privateChannel If the channel should be a private channel
    * @return Details needed to complete the opening of the channel
    */
  def initPSBTChannelOpen(
      nodeId: NodeId,
      peerAddrOpt: Option[InetSocketAddress],
      fundingAmount: CurrencyUnit,
      privateChannel: Boolean): Future[OutputDetails] = {
    logger.trace("lnd calling openchannel")

    // generate random channel id
    val chanId = ECPrivateKey.freshPrivateKey.bytes
    val shim = PsbtShim(pendingChanId = chanId, noPublish = true)
    val fundingShim = FundingShim(FundingPsbtShim(shim))

    val request =
      OpenChannelRequest(fundingShim = Some(fundingShim),
                         nodePubkey = nodeId.bytes,
                         localFundingAmount = fundingAmount.satoshis.toLong,
                         `private` = privateChannel)

    val connectF = peerAddrOpt match {
      case Some(addr) =>
        lndRpcClient.connectPeer(nodeId, addr)
      case None => Future.unit
    }

    connectF.flatMap { _ =>
      lnd
        .openChannel(request)
        .map(_.update)
        .filter(_.isPsbtFund)
        .collect { case fund: PsbtFund =>
          val amt = Satoshis(fund.value.fundingAmount)
          val addr = BitcoinAddress.fromString(fund.value.fundingAddress)

          api.OutputDetails(chanId, amt, addr)
        }
        .runWith(Sink.head)
    }
  }

  def fundPendingChannel(chanId: ByteVector, psbt: PSBT): Future[Unit] = {
    logger.trace("lnd calling fundingStateStep")

    val verify =
      FundingPsbtVerify(pendingChanId = chanId,
                        fundedPsbt = psbt.bytes,
                        skipFinalize = true)
    val verifyMsg = FundingTransitionMsg(PsbtVerify(verify))

    val fundF = lnd.fundingStateStep(verifyMsg).map(_ => ())

    fundF.failed.foreach { err =>
      logger.error("Failed to fundPendingChannel", err)
    }
    fundF
  }

  /** This should be called to cancel the channel if fundPendingChannel
    * has not been called
    * @param chanId Temporary id given to the channel
    * @return
    */
  def cancelPendingChannel(chanId: ByteVector): Future[Unit] = {
    logger.trace("lnd calling fundingStateStep")

    val cancel = ShimCancel(FundingShimCancel(chanId))
    val verifyMsg = FundingTransitionMsg(cancel)

    val fundF = lnd.fundingStateStep(verifyMsg).map(_ => ())

    fundF.failed.foreach { err =>
      logger.error("Failed to cancelPendingChannel", err)
    }
    fundF
  }

  /** This should be called to cancel the channel if fundPendingChannel
    * has been called
    * @param chanOutPoint TransactionOutPoint of the resulting channel
    * @return
    */
  def cancelChannel(chanOutPoint: TransactionOutPoint): Future[Unit] = {
    lndRpcClient.abandonChannel(chanOutPoint, pendingFundingShimOnly = true)
  }
}
