package com.github.hectormips

import Chisel.Cat
import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util.experimental.forceName
import com.github.hectormips.cache.access_judge.MemAccessJudge
import com.github.hectormips.cache.cache.Cache
import com.github.hectormips.cache.setting.CacheConfig
import com.github.hectormips.axi_crossbar_2x1
import chisel3.util.experimental.forceName


class SocTopSRamLikeBundle extends Bundle {
  val axi_io   : AXIIO       = new AXIIO(1)
  val interrupt: UInt        = Input(UInt(6.W))
  val debug    : DebugBundle = new DebugBundle
  forceName(debug.debug_wb_pc, "debug_wb_pc")
  forceName(debug.debug_wb_rf_wen, "debug_wb_rf_wen")
  forceName(debug.debug_wb_rf_wnum, "debug_wb_rf_wnum")
  forceName(debug.debug_wb_rf_wdata, "debug_wb_rf_wdata")
}


// 使用axi的Soc顶层
class SocTopAXI extends Module {
  val io: SocTopSRamLikeBundle = IO(new SocTopSRamLikeBundle)
  withReset(!reset.asBool()) {
    val cpu_top  : CpuTopSRamLike   = Module(new CpuTopSRamLike(0xbfbffffcL, 0))
    val cache    : Cache            = Module(new Cache(new CacheConfig()))
    val crossbar : axi_crossbar_2x1 = Module(new axi_crossbar_2x1)
    val mem_judge: MemAccessJudge   = Module(new MemAccessJudge(false.B))


    io.axi_io.force_name()
    cpu_top.io.interrupt := io.interrupt

    io.debug.debug_wb_pc := Cat(cpu_top.io.debug.debug_wb_pc, cpu_top.io.debug.debug_wb_pc)
    io.debug.debug_wb_rf_wnum := Cat(cpu_top.io.debug.debug_wb_rf_wnum, cpu_top.io.debug.debug_wb_rf_wnum)
    io.debug.debug_wb_rf_wen := Cat(cpu_top.io.debug.debug_wb_rf_wen, cpu_top.io.debug.debug_wb_rf_wen)
    io.debug.debug_wb_rf_wdata := Cat(cpu_top.io.debug.debug_wb_rf_wdata, cpu_top.io.debug.debug_wb_rf_wdata)


    mem_judge.io.inst.req := cpu_top.io.inst_sram_like_io.req
    mem_judge.io.inst.wr := cpu_top.io.inst_sram_like_io.wr
    mem_judge.io.inst.size := cpu_top.io.inst_sram_like_io.size
    mem_judge.io.inst.addr := cpu_top.io.inst_sram_like_io.addr
    mem_judge.io.inst.wdata := cpu_top.io.inst_sram_like_io.wdata
    mem_judge.io.inst.wr := cpu_top.io.inst_sram_like_io.wr
    cpu_top.io.inst_sram_like_io.addr_ok := mem_judge.io.inst.addr_ok
    cpu_top.io.inst_sram_like_io.data_ok := mem_judge.io.inst.data_ok
    cpu_top.io.inst_sram_like_io.rdata := mem_judge.io.inst.rdata
    cpu_top.io.inst_sram_like_io.inst_valid := mem_judge.io.inst.inst_valid
    cpu_top.io.inst_sram_like_io.inst_pc := mem_judge.io.inst.inst_pc

    mem_judge.io.data(0) <> cpu_top.io.data_sram_like_io(0)
    mem_judge.io.data(1) <> cpu_top.io.data_sram_like_io(1)


    mem_judge.io.cached_inst <> cache.io.icache
    mem_judge.io.cached_data <> cache.io.dcache
    mem_judge.io.uncached_data <> cache.io.uncached
    mem_judge.io.uncached_inst <> cache.io.uncache_inst

    cache.io.axi <> crossbar.io.in

    crossbar.io.aclk := clock
    crossbar.io.aresetn := reset.asBool() // reset在上面取反了
    crossbar.io.s_arqos := 0.U
    crossbar.io.s_awqos := 0.U
    io.axi_io <> crossbar.io.out


  }
  forceName(clock, "aclk")
  forceName(reset, "aresetn")
  forceName(io.interrupt, "ext_int")
  override val desiredName = s"mycpu_top"
}

object SocTopAXI extends App {
  (new ChiselStage).emitVerilog(new SocTopAXI)
}