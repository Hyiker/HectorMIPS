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
//    val debug_total_count = Output(UInt(32.W))  // cache总查询次数
//    val debug_pure_hit_count = Output(UInt(32.W))
//    val debug_hit_count   = Output(UInt(32.W))  // cache命中数

  })
  val sIDLE::sLOOKUP::sREPLACE::sREFILL::sWaiting::sEviction::sEvictionWaiting::Nil =Enum(7)
  val state = RegInit(VecInit(Seq.fill(2)(0.U(3.W))))

  val queue = Module(new Queue(new QueueItem, 2))
  val port_valid = RegInit(VecInit(Seq.fill(2)(true.B)))
  val storeBuffer = Module(new StoreBuffer(7))

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

  storeBuffer.io.cpu_wdata := 0.U
  storeBuffer.io.cpu_req := false.B
  storeBuffer.io.cpu_addr := 0.U
  storeBuffer.io.cpu_size := 0.U
  val break_loop_0 = RegInit(false.B)
  val break_loop_1 = RegInit(false.B)
  when(io.wr(0) && io.addr_ok(0) && io.valid(0)){
    storeBuffer.io.cpu_req  := true.B
    storeBuffer.io.cpu_size := io.size(0)
    storeBuffer.io.cpu_addr := io.addr(0)
    storeBuffer.io.cpu_wdata := io.wdata(0)
    break_loop_0 := true.B
  }

  when(io.wr(1) && io.addr_ok(1) && io.valid(1)){
    storeBuffer.io.cpu_req  := true.B
    storeBuffer.io.cpu_size := io.size(1)
    storeBuffer.io.cpu_addr := io.addr(1)
    storeBuffer.io.cpu_wdata := io.wdata(1)
    break_loop_1 := true.B
  }
  when(break_loop_0){
    break_loop_0 := false.B
    io.data_ok(0) := true.B
  }
  when(break_loop_1){
    break_loop_1 := false.B
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
//  val wstrb = Module(new Wstrb())


  val lruMem = Module(new LruMem(config.wayNumWidth,config.indexWidth))// lru
//  val victim = Module(new Victim(config)) // 写代理
  val invalidateQueue = Module(new InvalidateQueue(new CacheConfig()))

  io.axi.writeAddr <> invalidateQueue.io.writeAddr
  io.axi.writeData <> invalidateQueue.io.writeData
  io.axi.writeResp <> invalidateQueue.io.writeResp

  val cache_hit_onehot = Wire(Vec(2,Vec(config.wayNum, Bool()))) // 命中的路
  val cache_hit_way = Wire(Vec(2,UInt(config.wayNumWidth.W)))

//  val addr_r = RegInit(0.U(32.W)) //地址寄存器
  val addr_r = Wire(Vec(2,UInt(32.W)))//地址寄存器
  addr_r(0) := Mux(queue.io.count===0.U,0.U,queue.io.deq.bits.addr)
  addr_r(1) := storeBuffer.io.cache_write_addr
//  val size_r = RegInit(2.U(2.W))
  val size_r = Wire(Vec(2,UInt(2.W)))
  size_r(0) := queue.io.deq.bits.size
  size_r(1) := 0.U
//  size_r(1) := storeBuffer.io.cache_write_size

//  val wr_r = Wire(Vec(2,Bool()))
//  wr_r(0) := queue.io.deq.bits.wr

  val wdata_r = Wire(UInt(32.W))
//  wdata_r(0) := queue.io.deq.bits.wdata
  wdata_r := storeBuffer.io.cache_write_wdata

  val port_r = Wire(UInt(1.W))
  port_r := queue.io.deq.bits.port

  val bData = new BankData(config)
  val tagvData = new TAGVData(config)
  val dirtyData = new DirtyData(config)

  val bDataWtBank = RegInit(VecInit(Seq.fill(2)((0.U((config.offsetWidth-2).W)))))
//  val AXI_readyReg = RegInit(VecInit(Seq.fill(2)((false.B))))

  val is_hitWay = Wire(Vec(2,Bool()))
//  val addrokReg = RegInit(false.B)
  val index  = Wire(Vec(2,UInt(config.indexWidth.W)))
  val bankIndex = Wire(Vec(2,UInt((config.offsetWidth-2).W)))
  val tag  = Wire(Vec(2,UInt(config.tagWidth.W)))

  val waySelReg =  RegInit(VecInit(Seq.fill(2)(0.U(config.wayNumWidth.W))))
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
    tagvMem(way).io.wea := tagvData.wEn(0)(way)
    tagvMem(way).io.web := tagvData.wEn(1)(way)
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
      dataMem(way)(bank).io.wea := Mux(bData.wEn(0)(way)(bank),"b1111".U,"b0000".U)
      dataMem(way)(bank).io.web := Mux(bData.wEn(1)(way)(bank),Mux(bank.U === bankIndex(1),storeBuffer.io.cache_write_wstrb,"h1111".U),"b0000".U)
      dataMem(way)(bank).io.ena := true.B
      dataMem(way)(bank).io.enb := true.B
    }
  }

  /**
   * dataMem
   */

  for(way <- 0 until  config.wayNum){
    tagvMem(way).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
    tagvData.read(0)(way).tag := tagvMem(way).io.douta(config.tagWidth-1,0)
    tagvData.read(0)(way).valid := tagvMem(way).io.douta(config.tagWidth)


    dirtyMem(way).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
    dirtyData.read(0)(way) := dirtyMem(way).io.douta
    for(bank <- 0 until config.bankNum){
      dataMem(way)(bank).io.addra := config.getIndexByExpression(state(0)===sIDLE,queue.io.deq.bits.addr,addr_r(0))
      bData.read(0)(way)(bank)  := dataMem(way)(bank).io.douta
    }
  }
  for(way <- 0 until  config.wayNum) {
    tagvMem(way).io.addrb := config.getIndex(addr_r(1))
    tagvData.read(1)(way).tag := tagvMem(way).io.doutb(config.tagWidth-1,0)
    tagvData.read(1)(way).valid := tagvMem(way).io.doutb(config.tagWidth)
    dirtyMem(way).io.addrb := config.getIndex(addr_r(1))
    dirtyData.read(1)(way) := dirtyMem(way).io.doutb
    for(bank <- 0 until config.bankNum) {
      dataMem(way)(bank).io.addrb := config.getIndex(addr_r(1))
      bData.read(1)(way)(bank) := dataMem(way)(bank).io.doutb //现在
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
      when(bData.wEn(0)(way)(bank)) {
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
      when(bData.wEn(1)(way)(bank)) {
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
    when(tagvData.wEn(0)(way)){//写使能
      m.io.dina := Cat(true.B,tag(0))
    }
    when(tagvData.wEn(1)(way)){//写使能
      m.io.dinb := Cat(true.B,tag(1))
    }
  }

  for(way<- 0 until config.wayNum){
    for(bank <- 0 until config.bankNum) {
      // 读端口的数据写使能
      bData.wEn(0)(way)(bank) := state(0)===sREFILL && waySelReg(0) === way.U && bDataWtBank(0) ===bank.U && !clock.asBool()
        //          (state===sREPLACE && victim.io.find && waySelReg === way.U) ||// 如果victim buffer里找到了
//        (state === sVictimReplace && waySelReg === way.U && bankIndex ===bank.U)||  //读/写命中victim
      bData.wEn(1)(way)(bank) := (state(1) === sLOOKUP  && is_hitWay(1) && cache_hit_way(1) === way.U && bankIndex(1) ===bank.U)|| // 写命中
        (state(1) === sREFILL && waySelReg(1) === way.U && bDataWtBank(1) ===bank.U) ||
        (state(1) === sREFILL && waySelReg(1) === way.U && bank.U === bankIndex(1))
    }
  }
  for(way<- 0 until config.wayNum){
    tagvData.wEn(0)(way) := (state(0) === sREFILL && waySelReg(0)===way.U)
//      (state === sVictimReplace  && waySelReg===way.U)
    tagvData.wEn(1)(way) := (state(1) === sREFILL && waySelReg(1)===way.U)

  }

  tagvData.write(0) := Cat(true.B,tag(0))
  tagvData.write(1) := Cat(true.B,tag(1))

//  bData(0).addr := index(0)
//  bData(1).addr := index(1)
//  tagvData.addr := index
  for(worker <- 0 to 1) {
    cache_hit_way(worker) := OHToUInt(cache_hit_onehot(worker))
    // 判断是否命中cache
    cache_hit_onehot(worker).indices.foreach(i => {
      cache_hit_onehot(worker)(i) := tagvData.read(worker)(i).tag === tag(worker) && tagvData.read(worker)(i).valid
    })
  }

  /**
   * wstrb初始化
   */
//  wstrb.io.offset := addr_r(1)(1,0)
//  wstrb.io.size := size_r(1)

  /**
   * LRU 配置
   */
  lruMem.io.setAddr := Mux(state(0)===sLOOKUP,index(0),index(1))
  lruMem.io.visit := 0.U
  lruMem.io.visitValid := (state(0)===sLOOKUP && is_hitWay(0)|| state(1)===sLOOKUP && is_hitWay(1))

  /**
   * store buffer配置
   */
  storeBuffer.io.cache_query_addr := addr_r(0)
  val storeBuffer_reverse_mask = Wire(UInt(32.W))
  storeBuffer_reverse_mask := ~storeBuffer.io.cache_query_mask
  storeBuffer.io.cache_response := false.B
  /**
   * AXI
   */

  io.axi.readAddr.bits.id := 1.U// 未填
  val worker_id = Wire(Vec(2,UInt(4.W)))
  worker_id(0) := 1.U
  worker_id(1) := 3.U
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
  val evictionCounter =   RegInit(VecInit(Seq.fill(2)(0.U(config.bankNumWidth.W))))
  for(i <- 0 to 1) {
    invalidateQueue.io.addr(i) := Cat(tagvData.read(i)(waySelReg(i)).tag, index(i), 0.U(config.offsetWidth.W))
    invalidateQueue.io.wdata(i) := bData.read(i)(waySelReg(i))(evictionCounter(i))
    invalidateQueue.io.req(i) := false.B
  }
  /**
   * Cache状态机
   */


  io.axi.readAddr.valid :=  false.B
//  victim.io.qaddr := Cat(addr_r(31,config.offsetWidth),0.U(config.offsetWidth.W))
  for(worker <- 0 to 1) {
    switch(state(worker)) {
      is(sIDLE) {
        when(worker.U === 0.U && queue.io.count =/= 0.U) {
          when(addr_r(1)(31,2) === addr_r(0)(31,2)){
            when(state(1)===sIDLE) {
              state(worker) := sLOOKUP
            }.otherwise{
              state(worker) := sIDLE
            }
          }.otherwise{
            state(worker) := sLOOKUP
          }
        }.elsewhen(worker.U === 1.U && storeBuffer.io.cache_write_valid){
          when(addr_r(1) === addr_r(0)){
            when(state(0)===sWaiting){// 让0端口先走
              state(worker) := sLOOKUP
            }.otherwise{
              state(worker) := sIDLE
            }
          }.otherwise{
            state(worker) := sLOOKUP
          }
        }.otherwise{
          state(worker) := sIDLE
        }
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
            io.rdata(port_r) := ( bData.read(0)(cache_hit_way(0))(bankIndex(0)) & storeBuffer_reverse_mask ) |
              (storeBuffer.io.cache_query_data & storeBuffer.io.cache_query_mask)
          }.elsewhen(worker.U===1.U){
            storeBuffer.io.cache_response := true.B
          }
        }.otherwise {
          //没命中,检查victim
          //        state := sCheckVictim
          //        victim.io.qvalid := true.B
          when(worker.U===0.U && state(1)===sREPLACE || worker.U===1.U && state(0)===sREPLACE){
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
          io.axi.readAddr.bits.id := worker_id(worker)
          io.axi.readAddr.bits.addr := addr_r(worker)
          when(dirtyData.read(worker)(waySelReg(worker)) === true.B) {
            state(worker) := sEvictionWaiting
            invalidateQueue.io.req(worker) := true.B
          }.otherwise {
            state(worker) := sREFILL
          }
        }.otherwise {
          state(worker) := sREPLACE
          io.axi.readAddr.valid := true.B
          io.axi.readAddr.bits.id := worker_id(worker)
          io.axi.readAddr.bits.addr := addr_r(worker)
        }
      }
      is(sEvictionWaiting) {
        when(invalidateQueue.io.data_start(worker)) {
          state(worker) := sEviction
          evictionCounter(worker) := 0.U
        }.otherwise {
          state(worker) := sEvictionWaiting
        }
      }
      is(sEviction) {
        evictionCounter(worker) := evictionCounter(worker) + 1.U
        state(worker) := sEviction
        when(evictionCounter(worker) === (config.bankNum - 1).U) {
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
                io.rdata(port_r) := (bData.read(worker)(waySelReg(worker))(bankIndex(worker)) & storeBuffer_reverse_mask) |
                  (storeBuffer.io.cache_query_data & storeBuffer.io.cache_query_mask)
                io.data_ok(port_r) := true.B
            }.otherwise{
              state(worker) := sIDLE
              storeBuffer.io.cache_response := true.B
            }
          }
        }
      }
      is(sWaiting) {
        state(worker) := sIDLE

        io.rdata(port_r) := (bData.read(worker)(waySelReg(worker))(bankIndex(worker)) & storeBuffer_reverse_mask) |
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