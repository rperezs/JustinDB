package justin.db

import justin.db.ReplicaWriteAgreement.WriteAgreement
import justin.db.StorageNodeActorProtocol.StorageNodeWritingResult
import justin.db.replication.W

class ReplicaWriteAgreement {

  // TODO: more cases should be taken into account e.g. what if all writes were failed or one of it is conflicted?
  def reach(w: W): List[StorageNodeWritingResult] => WriteAgreement = {
    writes =>
      val okWrites = writes.count(_ == StorageNodeWritingResult.SuccessfulWrite)
      if(okWrites >= w.w) WriteAgreement.Ok else WriteAgreement.NotEnoughWrites
  }
}

object ReplicaWriteAgreement {

  sealed trait WriteAgreement
  object WriteAgreement {
    case object NotEnoughWrites extends WriteAgreement
    case object Ok extends WriteAgreement
  }
}
