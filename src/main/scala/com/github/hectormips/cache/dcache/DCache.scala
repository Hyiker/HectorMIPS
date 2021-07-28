package com.github.hectormips.cache.dcache

import com.github.hectormips.amba._
import com.github.hectormips.cache.setting.CacheConfig
import com.github.hectormips.cache.lru.LruMem
import com.github.hectormips.cache.utils.Wstrb
import chisel3._
import chisel3.util._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}



class QueueItem extends Bundle{
  val port = UInt(1.W)
  val addr = UInt(32.W)
  val wr   = Bool()
  val size = UInt(2.W)
  val wdata = UInt(32.W)
}


class DCache(val config:CacheConfig)
  extends Module {
  var io = IO(new Bundle {
    val valid = Input(Vec(2,Bool()))
    val addr = Input(Vec(2,UInt(32.W)))
    val addr_ok = Output(Vec(2,Bool()))

    val wr   = Input(Vec(2,Bool()))
    val size = Input(Vec(2,UInt(2.W)))
    val data_ok = Output(Vec(2,Bool()))

    val rdata = Output(Vec(2,UInt(32.W)))

    val wdata = Input(Vec(2,UInt(32.W)))

    val axi     = new Bundle{
      val readAddr  =  Decoupled(new AXIAddr(32,4))
      val readData  = Flipped(Decoupled(new AXIReadData(32,4)))
      val writeAddr  =  Decoupled(new AXIAddr(32,4))
      val writeData  = Decoupled(new AXIWriteData(32,4))
      val writeResp =  Flipped(Decoupled( new AXIWriteResponse(4)))
    }
    val debug_total_count = Output(UInt(32.W))  // cache总查询次数
    val debug_pure_hit_count = Output(UInt(32.W))
    val debug_hit_count   = Output(UInt(32.W))  // cache命中数

  })
  val sIDLE::sLOOKUP::sREPLACE::sREFILL::sWaiting::sEviction::sEvictionWaiting::Nil =Enum(7)
  val state = RegInit(VecInit(Seq.fill(2)(0.U(3.W))))

  val queue = Module(new Queue(new QueueItem, 2))
  val port_valid = RegInit(VecInit(Seq.fill(2)(true.B)))
  io.data_ok(1):= false.B
  io.data_ok(0):= false.B
  io.rdata(1) := 0.U
  io.rdata(0) := 0.U
  val polling = RegInit(false.B)
  polling := ~polling
  io.addr_ok(0) := port_valid(0) && polling && storeBuffer.io.cpu_ok
  io.addr_ok(1) := port_valid(1) && !polling && storeBuffer.io.cpu_ok
  when(queue.io.enq.ready){
    when(io.valid(0) && io.addr_ok(0) && !io.wr(0)){
      queue.io.enq.valid := true.B
      queue.io.enq.bits.addr := io.addr(0)
      queue.io.enq.bits.port := 0.U
      queue.io.enq.bits.wr := io.wr(0)
      queue.io.enq.bits.size := io.size(0)
      queue.io.enq.bits.wdata := io.wdata(0)
      port_valid(0) := false.B
    }.elsewhen(io.valid(1) && io.addr_ok(1) && !io.wr(1)){
      queue.io.enq.bits.addr := io.addr(1)
      queue.io.enq.bits.port := 1.U
      queue.io.enq.bits.wr := io.wr(1)
      queue.io.enq.bits.size := io.size(1)
      queue.io.enq.bits.wdata := io.wdata(1)
      queue.io.enq.valid := true.B
      port_valid(1) := false.B
    }.otherwise{
      queue.io.enq.bits := DontCare
      queue.io.enq.valid := false.B
    }
  }.otherwise{
    queue.io.enq.bits := DontCare
    queue.io.enq.valid := false.B
  }
  queue.io.deq.ready := false.B


  when(io.wr(0) && io.addr_ok(0) && io.valid(0)){
    storeBuffer.io.cpu_req  := true.B
    storeBuffer.io.cpu_size := io.size
    storeBuffer.io.cpu_addr := io.addr
    storeBuffer.io.cpu_wdata := io.wdata
    io.data_ok(0) := true.B
  }
  when(io.wr(1) && io.addr_ok(1) && io.valid(1)){
    storeBuffer.io.cpu_req  := true.B
    storeBuffer.io.cpu_size := io.size
    storeBuffer.io.cpu_addr := io.addr
    storeBuffer.io.cpu_wdata := io.wdata
    io.data_ok(1) := true.B
  }


  /**
   * cache的数据
   */
  val dataMem = List.fill(config.wayNum) {
    List.fill(config.bankNum) { // 4字节长就有4个
      Module(new dcache_data_bank(config.lineNum))
    }
  }
  val tagvMem = List.fill(config.wayNum) {
    Module(new dcache_tagv(config.tagWidth,config.lineNum))
  }
  val dirtyMem = List.fill(config.wayNum) {
    Module(new dcache_dirty(config.lineNum))
  }

//  val selection = Module(new ByteSelection)
  val wstrb = Module(new Wstrb())


  val lruMem = Module(new LruMem(config.wayNumWidth,config.indexWidth))// lru
  val storeBuffer = Module(new StoreBuffer(7))
//  val victim = Module(new Victim(config)) // 写代理
  val invalidateQueue = Module(new InvalidateQueue(new CacheConfig()))

  io.axi.writeAddr <> invalidateQueue.io.writeAddr
  io.axi.writeData <> invalidateQueue.io.writeData
  io.axi.writeResp <> invalidateQueue.io.writeResp

  val cache_hit_onehot = Wire(Vec(2,Vec(config.wayNum, Bool()))) // 命中的路
  val cache_hit_way = Wire(Vec(2,UInt(config.wayNumWidth.W)))

//  val addr_r = RegInit(0.U(32.W)) //地址寄存器
  val addr_r = Wire(Vec(2,UInt(32.W)))//地址寄存器
  addr_r(0) := queue.io.deq.bits.addr
  addr_r(1) := storeBuffer.io.cache_write_addr
//  val size_r = RegInit(2.U(2.W))
  val size_r = Wire(Vec(2,UInt(2.W)))
  size_r(0) := queue.io.deq.bits.size
  addr_r(1) := storeBuffer.io.cache_write_size

//  val wr_r = Wire(Vec(2,Bool()))
//  wr_r(0) := queue.io.deq.bits.wr

  val wdata_r = Wire(UInt(32.W))
//  wdata_r(0) := queue.io.deq.bits.wdata
  wdata_r := queue.io.deq.bits.wdata

  val port_r = Wire(UInt(1.W))
  port_r := queue.io.deq.bits.port

  val bData = Vec(2,new BankData(config))
  val tagvData = Vec(2,new TAGVData(config))
  val dirtyData = Vec(2,new DirtyData(config))

  val bDataWtBank = RegInit(VecInit(Seq.fill(2)((0.U((config.offsetWidth-2).W)))))
//  val AXI_readyReg = RegInit(VecInit(Seq.fill(2)((false.B))))

  val is_hitWay = Wire(Vec(2,Bool()))
//  val addrokReg = RegInit(false.B)
  val index  = Wire(Vec(2,UInt(config.indexWidth.W)))
  val bankIndex = Wire(Vec(2,UInt((config.offsetWidth-2).W)))
  val tag  = Wire(Vec(2,UInt(config.tagWidth.W)))

  val waySelReg = RegInit(Vec(2,0.U(config.wayNumWidth.W)))
//  val eviction = Wire(Bool()) //出现驱逐
  for(i <- 0 to 1) {
    is_hitWay(i) := cache_hit_onehot(i).asUInt().orR() // 判断是否命中cache
    state(i) := sIDLE
    index(i) := config.getIndex(addr_r(i))
    bankIndex(i) := config.getBankIndex(addr_r(i)) //offset去掉尾部2位
    tag(i) := config.getTag(addr_r(i))
  }
//  io.addr_ok := state === sIDLE


  /**
   * 初始化 ram
   */
  //初始化
  for(way <- 0 until config.wayNum){
    tagvMem(way).io.clka := clock
    tagvMem(way).io.clkb := clock
    tagvMem(way).io.wea := tagvData(0).wEn(way)
    tagvMem(way).io.web := tagvData(1).wEn(way)
    tagvMem(way).io.ena := true.B
    tagvMem(way).io.enb := true.B
    tagvMem(way).io.dina := 0.U
    tagvMem(way).io.dinb := 0.U
    dirtyMem(way).io.clka := clock
    dirtyMem(way).io.clkb := clock
    dirtyMem(way).io.wea := false.B
    dirtyMem(way).io.web := false.B
    dirtyMem(way).io.ena := true.B
    dirtyMem(way).io.enb := true.B
    for(bank <- 0 until config.bankNum){
      dataMem(way)(bank).io.clka := clock
      dataMem(way)(bank).io.clkb := clock
      dataMem(way)(bank).io.wea := Mux(bData(0).wEn(way)(bank),"b1111".U,"b0000".U)
      dataMem(way)(bank).io.web := Mux(bData(0).wEn(way)(bank),wstrb.io.mask,"b0000".U)
      dataMem(way)(bank).io.ena := true.B
      dataMem(way)(bank).io.enb := true.B
    }
  }

  /**
   * dataMem
   */

  for(way <- 0 until  config.wayNum){
    tagvMem(way).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
    tagvData(0).read(way).tag := tagvMem(way).io.douta(config.tagWidth-1,0)
    tagvData(0).read(way).valid := tagvMem(way).io.douta(config.tagWidth)
    tagvMem(way).io.addrb := config.getIndex(addr_r(1))
    tagvData(1).read(way).tag := tagvMem(way).io.douta(config.tagWidth-1,0)
    tagvData(1).read(way).valid := tagvMem(way).io.douta(config.tagWidth)

    dirtyMem(way).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
    dirtyData(0).read(way) := dirtyMem(way).io.douta
    dirtyMem(way).io.addrb := config.getIndex(addr_r(1))
    dirtyData(0).read(way) := dirtyMem(way).io.douta
    for(bank <- 0 until config.bankNum){
      dataMem(way)(bank).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
      bData(0).read(way)(bank)  := dataMem(way)(bank).io.douta
      dataMem(way)(bank).io.addrb := config.getIndex(addr_r(1))
      bData(1).read(way)(bank)  := dataMem(way)(bank).io.douta
    }
  }

  /**
   * 读、写端口的控制
   */
  dataMem.indices.foreach(way => {
    dataMem(way).indices.foreach(bank => {
      val m = dataMem(way)(bank)

      /**
       * 读口 控制
       */
      when(bData(0).wEn(way)(bank)) {
        when(state(0) === sREFILL) {
          dirtyMem(way).io.wea := true.B
          dirtyMem(way).io.dina := false.B
          when(bank.U === bDataWtBank(0)){
              m.io.dina  :=  io.axi.readData.bits.data
          }
        }
      }

      /**
       * 写 口控制
       */
      when(bData(1).wEn(way)(bank)) {
        when(state(1) === sREFILL) {
          dirtyMem(way).io.web := true.B
          dirtyMem(way).io.dinb := true.B
          when(bank.U === bDataWtBank(1)){
            m.io.dinb  :=  io.axi.readData.bits.data
          }.otherwise{
            when(bank.U === bankIndex(1)){
              m.io.dinb := wdata_r
            }
          }
        }.elsewhen(state(1)===sLOOKUP && is_hitWay(1) && cache_hit_way(1) === way.U){
          // write hit
          dirtyMem(way).io.web := true.B
          dirtyMem(way).io.dinb := true.B //旧数据
          when(bank.U === bankIndex(1)){
            m.io.dinb := wdata_r
          }
        }
      }
    })
  })


  for(way <- 0 until config.wayNum){
    val m = tagvMem(way)
    when(tagvData(0).wEn(way)){//写使能
      m.io.dina := Cat(true.B,tag(0))
    }
    when(tagvData(1).wEn(way)){//写使能
      m.io.dinb := Cat(true.B,tag(1))
    }
  }

  for(way<- 0 until config.wayNum){
    for(bank <- 0 until config.bankNum) {
      // 读端口的数据写使能
      bData(0).wEn(way)(bank) := (state(0)===sREFILL && waySelReg(0) === way.U && bDataWtBank(0) ===bank.U && !clock.asBool() )
        //          (state===sREPLACE && victim.io.find && waySelReg === way.U) ||// 如果victim buffer里找到了
//        (state === sVictimReplace && waySelReg === way.U && bankIndex ===bank.U)||  //读/写命中victim
      bData(1).wEn(way)(bank) := (state(1) === sLOOKUP  && cache_hit_way(1) === way.U && bankIndex(1) ===bank.U)|| // 写命中
        (is_hitWay(1) && state(1) === sREFILL && waySelReg(1) === way.U && bDataWtBank(1) ===bank.U)
    }
  }
  for(way<- 0 until config.wayNum){
    tagvData(0).wEn(way) := (state(0) === sREFILL && waySelReg(0)===way.U)
//      (state === sVictimReplace  && waySelReg===way.U)
    tagvData(1).wEn(way) := (state(1) === sREFILL && waySelReg(1)===way.U)

  }

  tagvData(0).write := Cat(true.B,tag(0))
  tagvData(1).write := Cat(true.B,tag(1))

//  bData(0).addr := index(0)
//  bData(1).addr := index(1)
//  tagvData.addr := index
  for(worker <- 0 to 1) {
    cache_hit_way(worker) := OHToUInt(cache_hit_onehot(worker))
    // 判断是否命中cache
    cache_hit_onehot(worker).indices.foreach(i => {
      cache_hit_onehot(worker)(i) := tagvData(worker).read(i).tag === tag(worker) && tagvData(worker).read(i).valid
    })
  }

  /**
   * wstrb初始化
   */
  wstrb.io.offset := addr_r(1)(1,0)
  wstrb.io.size := size_r(1)

  /**
   * LRU 配置
   */
  lruMem.io.setAddr := index
  lruMem.io.visit := 0.U
  lruMem.io.visitValid := is_hitWay

  /**
   * store buffer配置
   */
  storeBuffer.io.cache_query_addr := addr_r
  val storeBuffer_reverse_mask = UInt(32.W)
  storeBuffer_reverse_mask := ~storeBuffer.io.cache_query_mask

  /**
   * AXI
   */

  io.axi.readAddr.bits.id := 1.U// 未填
  val worker_id = Wire(Vec(2,UInt(4.W)))
  worker_id(0) := 3.U
  worker_id(1) := 4.U
  //  io.axi.readAddr.bits.len := 6.U
  io.axi.readAddr.bits.len := (config.bankNum - 1).U
  io.axi.readAddr.bits.size := 2.U // 4B
  io.axi.readAddr.bits.addr := 0.U // 未填
  io.axi.readAddr.bits.cache := 0.U
  io.axi.readAddr.bits.lock := 0.U
  io.axi.readAddr.bits.prot := 0.U
  io.axi.readAddr.bits.burst := 2.U //突发模式2
  io.axi.readData.ready := state(0) ===  sREFILL || state(1) === sREFILL
  //  /**
//   * debug
//   */
//  val debug_total_count_r = RegInit(0.U(32.W))
//  val debug_hit_count_r = RegInit(0.U(32.W))
//  val debug_pure_hit_count_r = RegInit(0.U(32.W))
//  io.debug_pure_hit_count := debug_pure_hit_count_r
//  io.debug_total_count := debug_total_count_r
//  io.debug_hit_count := debug_hit_count_r
//
//  when(state===sLOOKUP){
//    debug_total_count_r := debug_total_count_r + 1.U
//    when(is_hitWay){
//      debug_pure_hit_count_r := debug_pure_hit_count_r + 1.U
//      debug_hit_count_r := debug_hit_count_r + 1.U
//    }
//  }
//  when(state === sVictimReplace){
//    debug_hit_count_r := debug_hit_count_r + 1.U
//  }

  /**
   * 驱逐
   */
  val evictionCounter = RegInit(0.U(config.bankNumWidth.W))
  invalidateQueue.io.addr := Cat(tagvData.read(waySelReg).tag,index,0.U(config.offsetWidth.W))
  invalidateQueue.io.wdata := bData.read(waySelReg)(evictionCounter)
  invalidateQueue.io.req := false.B
  /**
   * Cache状态机
   */


  io.axi.readAddr.valid :=  false.B
//  victim.io.qaddr := Cat(addr_r(31,config.offsetWidth),0.U(config.offsetWidth.W))
  for(worker <- 0 to 1) {
    switch(state(worker)) {
      is(sIDLE) {
        when(worker.U === 0.U && queue.io.count =/= 0.U) {
          when(addr_r(1) === addr_r(0)){
            when(state(1)===sIDLE || state(1)===sREFILL && io.axi.readData.bits.last){
              state := sLOOKUP
            }.otherwise{
              state := sIDLE
            }
          }.otherwise{
            state := sLOOKUP
          }
        }
        when(worker.U === 1.U && storeBuffer.io.cache_write_valid){
          when(addr_r(1) === addr_r(0)){
            when(state(0)===sWaiting){// 让0端口先走
              state := sLOOKUP
            }.otherwise{
              state := sIDLE
            }
          }.otherwise{
            state := sLOOKUP
          }
        }
        state(worker) := sIDLE
      }
      is(sLOOKUP) {
        when(is_hitWay(worker)) {
          lruMem.io.visit := cache_hit_way(worker) // lru记录命中
          state(worker) := sIDLE
          //        when(io.valid) {
          //          // 直接进入下一轮
          //          state := sLOOKUP
          //          addr_r := io.addr
          //          size_r := io.size
          //          wr_r := io.wr
          //          wdata_r := io.wdata
          //        }.otherwise {
          //
          //        }
          when(worker.U === 0.U) {
            port_valid(port_r) := true.B
            io.data_ok(port_r) := true.B
            queue.io.deq.ready := true.B
            io.rdata(port_r) := ( bData(0).read(cache_hit_way(0))(bankIndex(0)) & storeBuffer_reverse_mask ) |
              (storeBuffer.io.cache_query_data & storeBuffer.io.cache_query_mask)
          }
        }.otherwise {
          //没命中,检查victim
          //        state := sCheckVictim
          //        victim.io.qvalid := true.B
          when(state(~worker)===sREPLACE){
            state(worker) := sLOOKUP
          }.otherwise{
            io.axi.readAddr.bits.id := worker_id(worker)
            io.axi.readAddr.bits.addr := addr_r(worker)
            io.axi.readAddr.valid := true.B
            state(worker) := sREPLACE
            waySelReg(worker) := lruMem.io.waySel
          }
        }
      }
      is(sREPLACE) {
        //在此阶段完成驱逐
        bDataWtBank(worker) := bankIndex(worker)
        when(io.axi.readAddr.ready) {
          when(dirtyData(worker).read(waySelReg(worker)) === true.B) {
            state(worker) := sEvictionWaiting
            invalidateQueue.io.req := true.B
          }.otherwise {
            state(worker) := sREFILL
          }
        }.otherwise {
          state := sREPLACE
          io.axi.readAddr.valid := true.B
          io.axi.readAddr.bits.id := worker_id(worker)
          io.axi.readAddr.bits.addr := addr_r(worker)
        }
      }
      is(sEvictionWaiting) {
        when(invalidateQueue.io.data_start) {
          state(worker) := sEviction
          evictionCounter := 0.U
        }.otherwise {
          state(worker) := sEvictionWaiting
        }
      }
      is(sEviction) {
        evictionCounter := evictionCounter + 1.U
        state(worker) := sEviction
        when(evictionCounter === (config.bankNum - 1).U) {
          state(worker) := sREFILL
        }
      }
      is(sREFILL) {
        // 取数据，重写TAGV
        state(worker) := sREFILL
        when(io.axi.readData.valid && io.axi.readData.bits.id === worker_id(worker)) {
          bDataWtBank(worker) := bDataWtBank(worker) + 1.U
          when(io.axi.readData.bits.last) {
            when(worker.U===0.U){
              state(worker) := sWaiting
                io.rdata(port_r) := (bData(worker).read(waySelReg(worker))(bankIndex(worker)) & storeBuffer_reverse_mask) |
                  (storeBuffer.io.cache_query_data & storeBuffer.io.cache_query_mask)
                io.data_ok(port_r) := true.B
            }.otherwise{
              state(worker) := sIDLE
            }
          }
        }
      }
      is(sWaiting) {
        state(worker) := sIDLE
        io.rdata(port_r) := (bData(worker).read(waySelReg(worker))(bankIndex(worker)) & storeBuffer_reverse_mask) |
          (storeBuffer.io.cache_query_data & storeBuffer.io.cache_query_mask)
        io.data_ok(port_r) := true.B
        port_valid(port_r) := true.B
        queue.io.deq.ready := true.B
      }
    }
  }

  /**
   * 驱逐写控制信号
   */
//  debug_counter := debug_counter + 1.U
//  fetch_ready_go := victim.io.fill_valid
//  when(state ===sEviction) {
//    // 替换掉数据到victim中
//    victim.io.wdata := bData.read(waySelReg)(victimEvictioncounter)
//  }.elsewhen(state === sCheckVictim && victim.io.find){
//    // sCheckVictim只会有一拍，因此不用担心lruMem.io.waySel会变化
//    // 从victim中取数据并替换
//    victim.io.wdata := DontCare
//  }.elsewhen(state === sVictimReplace){
//    // 从victim中取数据并替换
//    victim.io.waddr := DontCare
//    victim.io.wdata := RegNext(bData.read(waySelReg)(victimEvictioncounter))
//  }.otherwise{
//    victim.io.wdata := DontCare
//    victim.io.waddr := DontCare
//    //    eviction := false.B
//    victim.io.wvalid := false.B
//    victim.io.dirty := false.B
//    victim.io.wdata:=DontCare
//  }
//  victim.io.waddr := DontCare




}

object DCache extends App {
  new ChiselStage execute(args, Seq(ChiselGeneratorAnnotation(
    () =>
      new DCache(new CacheConfig()))))
}