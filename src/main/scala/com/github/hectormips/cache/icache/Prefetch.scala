package com.github.hectormips.cache.icache

import chisel3._
import chisel3.util._
import com.github.hectormips.amba.{AXIAddr, AXIReadData}
import com.github.hectormips.cache.setting.CacheConfig

import scala.collection.immutable.Nil


class BufferItem(bankNum:Int) extends Bundle{
  val valid = Bool()
  val addr  = UInt(32.W)
  val bank  = Vec(bankNum,UInt(32.W))
}
class Buffer(config:CacheConfig)extends Bundle{
  val data   = RegInit(VecInit(Seq.fill(config.prefetch_buffer_size)({
    val bundle = Wire(new BufferItem(bankNum = config.bankNum))
    bundle.valid := 0.U
    bundle.addr := 0.U
    for(i <- 0 until config.bankNum){
      bundle.bank(i) := 0.U
    }
    bundle
  })))
  val ptr = RegInit(0.U(log2Ceil(config.prefetch_buffer_size)))
}
/**
 * N+1行预取
 */
class Prefetch(config:CacheConfig) extends Module{
  val io = IO(new Bundle{
    /**
     * 预取请求
     * 如果预取器正忙，会忽略请求
     */
    val req_valid = Input(Bool())
    val req_ready = Output(Bool())
    val req_addr =Input(UInt(32.W))

    /**
     *  预取查询
     */
    val query_valid  = Input(Bool())
    val query_addr   = Input(UInt(32.W))
    val query_finded = Output(Bool())
    val query_data   = Output(Vec(config.bankNum,UInt(32.W)))

    /**
     * axi
     */
    val use_axi   = Output(Bool())
    val readAddr  =  Decoupled(new AXIAddr(32,4))
    val readData  = Flipped(Decoupled(new AXIReadData(32,4)))
  })
  val sIDLE::sHANDSHAKE::sREFILL::Nil = Enum(3)
  val state = RegInit(0.U(2.W))
  val buffer = new Buffer(config)
  val addr_r = RegInit(0.U(32.W))
  val bankCounter = RegInit(0.U(config.bankNumWidth.W))

  /**
   * 预取阶段
   */
  buffer.data(buffer.ptr).bank(bankCounter) := io.readData.bits.data
  io.use_axi := state === sHANDSHAKE
  io.req_ready := state === sIDLE
  when(state === sIDLE && io.req_valid && io.req_ready){
    addr_r := Cat(io.req_addr(31,config.offsetWidth),0.U(config.offsetWidth.W)) + (config.bankNum*4).U
    state := sHANDSHAKE
  }
  when(state === sHANDSHAKE){
    when(io.readAddr.valid && io.readAddr.ready){
      state := sREFILL
      bankCounter := 0.U
    }.otherwise{
      state := sHANDSHAKE
    }
  }
  when(state === sREFILL){
    when(io.readData.valid && io.readData.ready && io.readData.bits.id===1.U){
      bankCounter := bankCounter + 1.U
      when(io.readData.bits.last){
        state := sIDLE
        buffer.ptr := buffer.ptr + 1.U
      }.otherwise{
        state := sREFILL
      }
    }.otherwise{
      state := sREFILL
    }
  }

  /**
   * 查询阶段
   */
  val query_onehot = Wire(Vec(config.prefetch_buffer_size,Bool()))
  val query_hit    = Wire(UInt(log2Ceil(config.prefetch_buffer_size).W))
  query_hit := OHToUInt(query_onehot)
  for (i <- 0 until config.prefetch_buffer_size){
    query_onehot(i) := buffer.data(i).valid && buffer.data(i).addr(31,config.offsetWidth)===io.query_addr(31,config.offsetWidth)
  }
  io.query_finded := query_onehot.asUInt() =/= 0.U  && io.query_valid
  io.query_data := buffer.data(query_hit).bank


  /**
   * axi访问设置
   */
  dontTouch(io.readAddr.bits.id)
//  dontTouch(io.readData.bits.data)
//  dontTouch(io.readData.valid)
//  dontTouch(io.readData.ready)
  io.readAddr.bits.id := 1.U
  io.readAddr.bits.len := (config.bankNum - 1).U
  io.readAddr.bits.size := 2.U // 4B
  io.readAddr.bits.addr := addr_r
  io.readAddr.bits.cache := 0.U
  io.readAddr.bits.lock := 0.U
  io.readAddr.bits.prot := 0.U
  io.readAddr.bits.burst := 1.U //突发模式2
  io.readAddr.valid := state === sHANDSHAKE
  io.readData.ready := state === sREFILL  //ready最多持续一拍
}
