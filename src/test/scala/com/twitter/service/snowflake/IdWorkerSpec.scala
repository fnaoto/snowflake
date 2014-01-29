package com.twitter.service.snowflake

import org.specs._

class IdWorkerSpec extends SpecificationWithJUnit {
  val workerMask     = 0x000000000001F000L
  val datacenterMask = 0x00000000003E0000L
  val timestampMask  = 0xFFFFFFFFFFC00000L

  class EasyTimeWorker(workerId: Long, datacenterId: Long)
    extends IdWorker(workerId, datacenterId) {
    var timeMaker = (() => System.currentTimeMillis)
    override def timeGen(): Long = {
      timeMaker()
    }
  }

  class WakingIdWorker(workerId: Long, datacenterId: Long)
    extends EasyTimeWorker(workerId, datacenterId) {
    var slept = 0
    override def tilNextMillis(lastTimestamp:Long): Long = {
      slept += 1
      super.tilNextMillis(lastTimestamp)
    }
  }

  class StaticTimeWorker(workerId: Long, datacenterId: Long)
    extends IdWorker(workerId, datacenterId) {

    var time = 1L

    override def timeGen = time + twepoch
  }

  "IdWorker" should {

    "generate an id" in {
      val s = new IdWorker(1, 1)
      val id: Long = s.nextId()
      id must be_>(0L)
    }

    "properly mask worker id" in {
      val workerId = 0x1F
      val datacenterId = 0
      val worker = new IdWorker(workerId, datacenterId)
      for (i <- 1 to 1000) {
        val id = worker.nextId
        ((id & workerMask) >> 12) must be_==(workerId)
      }
    }

    "properly mask dc id" in {
      val workerId = 0
      val datacenterId = 0x1F
      val worker = new IdWorker(workerId, datacenterId)
      val id = worker.nextId
      ((id & datacenterMask) >> 17) must be_==(datacenterId)
    }

    "properly mask timestamp" in {
      val worker = new EasyTimeWorker(31, 31)
      for (i <- 1 to 100) {
        val t = System.currentTimeMillis
        worker.timeMaker = (() => t)
        val id = worker.nextId
        ((id & timestampMask) >> 22)  must be_==(t - worker.twepoch)
      }
    }

    "roll over sequence id" in {
      // put a zero in the low bit so we can detect overflow from the sequence
      val workerId = 4
      val datacenterId = 4
      val worker = new IdWorker(workerId, datacenterId)
      val startSequence = 0xFFFFFF-20
      val endSequence = 0xFFFFFF+20
      worker.sequence = startSequence

      for (i <- startSequence to endSequence) {
        val id = worker.nextId
        ((id & workerMask) >> 12) must be_==(workerId)
      }
    }

    "generate increasing ids" in {
      val worker = new IdWorker(1, 1)
      var lastId = 0L
      for (i <- 1 to 100) {
        val id = worker.nextId
        id must be_>(lastId)
        lastId = id
      }
    }

    "generate 1 million ids quickly" in {
      val worker = new IdWorker(31, 31)
      val t = System.currentTimeMillis
      for (i <- 1 to 1000000) {
        var id = worker.nextId
        id
      }
      val t2 = System.currentTimeMillis
      println("generated 1000000 ids in %d ms, or %,.0f ids/second".format(t2 - t, 1000000000.0/(t2-t)))
      1 must be_>(0)
    }

    "sleep if we would rollover twice in the same millisecond" in {
      var queue = new scala.collection.mutable.Queue[Long]()
      val worker = new WakingIdWorker(1, 1)
      val iter = List(2L, 2L, 3L).iterator
      worker.timeMaker = (() => iter.next)
      worker.sequence = 4095
      worker.nextId
      worker.sequence = 4095
      worker.nextId
      worker.slept must be(1)
    }

    "generate only unique ids" in {
      val worker = new IdWorker(31, 31)
      var set = new scala.collection.mutable.HashSet[Long]()
      val n = 2000000
      (1 to n).foreach{i =>
        val id = worker.nextId
        if (set.contains(id)) {
          println(java.lang.Long.toString(id, 2))
        } else {
          set += id
        }
      }
      set.size must be_==(n)
    }

    "generate ids over 50 billion" in {
      val worker = new IdWorker(0, 0)
      worker.nextId must be_>(50000000000L)
    }

    "generate only unique ids, even when time goes backwards" in {
      val sequenceMask = -1L ^ (-1L << 12)
      val worker = new StaticTimeWorker(0, 0)

      // reported at https://github.com/twitter/snowflake/issues/6
      // first we generate 2 ids with the same time, so that we get the sequqence to 1
      worker.sequence must be_==(0)
      worker.time     must be_==(1)
      val id1 = worker.nextId
      (id1 >> 22) must be_==(1)
      (id1 & sequenceMask) must be_==(0)

      worker.sequence must be_==(0)
      worker.time     must be_==(1)
      val id2 = worker.nextId
      (id2 >> 22) must be_==(1)
      (id2 & sequenceMask) must be_==(1)

      //then we set the time backwards

      worker.time = 0
      worker.sequence must be_==(1)
      worker.nextId must throwA[InvalidSystemClock]
      worker.sequence must be_==(1) // this used to get reset to 0, which would cause conflicts

      worker.time = 1
      val id3 = worker.nextId
      (id3 >> 22) must be_==(1)
      (id3 & sequenceMask ) must be_==(2)
    }
  }
}
