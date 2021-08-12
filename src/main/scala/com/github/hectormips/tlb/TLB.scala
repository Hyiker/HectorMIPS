package com.github.hectormips.tlb

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util._

// 查询接口
class SearchPort(TLBNUM: Int) extends Bundle {
  val vpn2     = Input(UInt(19.W))
  val odd_page = Input(UInt(1.W))
  val asid     = Input(UInt(8.W))
  val found    = Output(Bool())
  val index    = Output(UInt(log2Up(TLBNUM).W))
  val pfn      = Output(UInt(20.W))
  val c        = Output(UInt(3.W)) // cache标记
  val d        = Output(Bool()) // 脏位
  val v        = Output(Bool()) // 有效

  val ex = Output(UInt(3.W)) //例外，第一个bit表示重填例外，第二个bit表示无效例外，第三个bit表示修改例外
}

// TLB行
class TLBRow extends Bundle {
  val vpn2 = UInt(19.W)
  val asid = UInt(8.W)
  val g    = UInt(1.W)
  val PFN0 = UInt(20.W)
  val C0   = UInt(3.W)
  val D0   = UInt(1.W)
  val V0   = UInt(1.W)
  val PFN1 = UInt(20.W)
  val C1   = UInt(3.W)
  val D1   = UInt(1.W)
  val V1   = UInt(1.W)
}

class TLBWIBundle(tlb_num: Int) extends Bundle {
  val we        : Bool = Input(Bool())
  val w_index   : UInt = Input(UInt(log2Up(tlb_num).W)) // TLB表项id
  val w_vpn2    : UInt = Input(UInt(19.W))
  val w_asid    : UInt = Input(UInt(8.W))
  val w_g       : Bool = Input(Bool())
  val w_pfn0    : UInt = Input(UInt(20.W))
  val w_c0      : UInt = Input(UInt(3.W))
  val w_d0      : Bool = Input(Bool())
  val w_v0      : Bool = Input(Bool())
  val w_pfn1    : UInt = Input(UInt(20.W))
  val w_c1      : UInt = Input(UInt(3.W))
  val w_d1      : Bool = Input(Bool())
  val w_v1      : Bool = Input(Bool())
  val w_pagemask: UInt = Output(UInt(12.W))
}

class TLBRBundle(tlb_num: Int) extends Bundle {
  val r_index   : UInt = Input(UInt(log2Up(tlb_num).W)) // TLB表项id
  val r_vpn2    : UInt = Output(UInt(19.W))
  val r_asid    : UInt = Output(UInt(8.W))
  val r_g       : Bool = Output(Bool())
  val r_pfn0    : UInt = Output(UInt(20.W))
  val r_c0      : UInt = Output(UInt(3.W))
  val r_d0      : Bool = Output(Bool())
  val r_v0      : Bool = Output(Bool())
  val r_pfn1    : UInt = Output(UInt(20.W))
  val r_c1      : UInt = Output(UInt(3.W))
  val r_d1      : Bool = Output(Bool())
  val r_v1      : Bool = Output(Bool())
  val r_pagemask: UInt = Output(UInt(12.W))
}

class TLBPBundle(tlb_num: Int) extends Bundle {
  val p_vpn2 : UInt = Input(UInt(19.W))
  val p_asid : UInt = Input(UInt(8.W))
  val p_index: UInt = Output(UInt(log2Up(tlb_num).W)) // TLB表项id
  val p_find : Bool = Output(Bool())
}

class TLBInstBundle(tlb_num: Int) extends Bundle {
  // TLBWI，无例外
  val tlbwi_io: TLBWIBundle = new TLBWIBundle(tlb_num)

  //TLBR 使用index查询，无例外
  val tlbr_io: TLBRBundle = new TLBRBundle(tlb_num)

  //TLBP 使用VPN2和ASID 查询index，无例外
  val tlbp_io: TLBPBundle = new TLBPBundle(tlb_num)
}

class TLBBundle(TLBNUM: Int) extends Bundle {
  val s0 = new SearchPort(TLBNUM) // 查询端口1，供取指使用,有三种例外
  val s1 = new SearchPort(TLBNUM) // 查询端口2，供仿存使用R

  val tlb_inst_io: TLBInstBundle = new TLBInstBundle(TLBNUM)

}

class TLB(TLBNUM: Int) extends Module {
  /**
   * 书上压缩了页表(32-log2(4k)=20,而这里只有19)，VPN2低位忽略，表示两个4KB的页
   * --------------------------------------------------------
   * | VPN2 | ASID | G  | PFN0 | C0,D0,V0 | PFN1 | C1,D1,V1 |
   * | 19b  | 8b   |1b  | 20b  | 3  1  1  | 20b  | 3  1  1  |
   * --------------------------------------------------------
   *
   */
  //  chisel3.util.experimental.forceName(clock,"clk")
  val io     = IO(new TLBBundle(TLBNUM))
  val tlbrow = RegInit(VecInit(Seq.fill(TLBNUM)({
    val bundle = Wire(new TLBRow)
    bundle.vpn2 := 0.U
    bundle.asid := 0.U
    bundle.g := 0.U
    bundle.PFN0 := 0.U
    bundle.C0 := 0.U
    bundle.D0 := 0.U
    bundle.V0 := 0.U
    bundle.PFN1 := 0.U
    bundle.C1 := 0.U
    bundle.D1 := 0.U
    bundle.V1 := 0.U
    bundle
  })))

  /**
   * 查询
   */
  def tlb_match(tlbrow: Vec[TLBRow], s: SearchPort): Unit = {
    val match0 = Wire(Vec(TLBNUM, Bool()))
    val index0 = Wire(UInt(log2Up(TLBNUM).W))
    index0 := OHToUInt(match0.asUInt())
    val ex = Wire(Vec(3, Bool()))
    s.ex := Cat(ex(2), ex(1), ex(0))
    ex(2) := s.found && tlbrow(s.index).D0.asBool() //修改例外需要知道是否为store，因此由cache决定是否修改此位
    ex(1) := false.B
    ex(0) := false.B
    for (i <- 0 until TLBNUM)
    // vpn匹配 且 进程id匹配
      match0(i) := ((s.vpn2 === tlbrow(i).vpn2) && ((s.asid === tlbrow(i).asid) || (tlbrow(i).g.asBool())))
    when(match0.asUInt() === 0.U) {
      // 未找到
      s.found := false.B
      s.index := 0.U
      s.pfn := 0.U
      s.c := 0.U
      s.d := 0.U
      s.v := 0.U
      ex(0) := true.B //重填例外
    }.elsewhen((s.odd_page === false.B && tlbrow(index0).V0 =/= true.B) || (s.odd_page === true.B && tlbrow(index0).V1 =/= true.B)) {
      // invalid
      s.found := false.B
      s.index := 0.U
      s.pfn := 0.U
      s.c := 0.U
      s.d := 0.U
      s.v := 0.U
      ex(1) := true.B //无效例外
    }.otherwise {
      s.found := true.B
      s.index := index0
      s.pfn := Mux(s.odd_page.asBool(), tlbrow(index0).PFN1, tlbrow(index0).PFN0)
      s.c := Mux(s.odd_page.asBool(), tlbrow(index0).C1, tlbrow(index0).C0)
      s.d := Mux(s.odd_page.asBool(), tlbrow(index0).D1, tlbrow(index0).D0)
      s.v := Mux(s.odd_page.asBool(), tlbrow(index0).V1, tlbrow(index0).V0)
    }
  }

  tlb_match(tlbrow, io.s0)
  tlb_match(tlbrow, io.s1)

  when(io.tlb_inst_io.tlbwi_io.we) {
    /**
     * 写
     */
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).vpn2 := io.tlb_inst_io.tlbwi_io.w_vpn2
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).asid := io.tlb_inst_io.tlbwi_io.w_asid
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).g := io.tlb_inst_io.tlbwi_io.w_g
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).PFN0 := io.tlb_inst_io.tlbwi_io.w_pfn0
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).C0 := io.tlb_inst_io.tlbwi_io.w_c0
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).D0 := io.tlb_inst_io.tlbwi_io.w_d0
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).V0 := io.tlb_inst_io.tlbwi_io.w_v0
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).PFN1 := io.tlb_inst_io.tlbwi_io.w_pfn1
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).C1 := io.tlb_inst_io.tlbwi_io.w_c1
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).D1 := io.tlb_inst_io.tlbwi_io.w_d1
    tlbrow(io.tlb_inst_io.tlbwi_io.w_index).V1 := io.tlb_inst_io.tlbwi_io.w_v1
  }
  io.tlb_inst_io.tlbr_io.r_vpn2 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).vpn2
  io.tlb_inst_io.tlbr_io.r_asid := tlbrow(io.tlb_inst_io.tlbr_io.r_index).asid
  io.tlb_inst_io.tlbr_io.r_g := tlbrow(io.tlb_inst_io.tlbr_io.r_index).g
  io.tlb_inst_io.tlbr_io.r_pfn0 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).PFN0
  io.tlb_inst_io.tlbr_io.r_c0 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).C0
  io.tlb_inst_io.tlbr_io.r_d0 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).D0
  io.tlb_inst_io.tlbr_io.r_v0 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).V0
  io.tlb_inst_io.tlbr_io.r_pfn1 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).PFN1
  io.tlb_inst_io.tlbr_io.r_c1 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).C1
  io.tlb_inst_io.tlbr_io.r_d1 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).D1
  io.tlb_inst_io.tlbr_io.r_v1 := tlbrow(io.tlb_inst_io.tlbr_io.r_index).V1
  io.tlb_inst_io.tlbr_io.r_pagemask := 0.U //只能使用4KB

  /**
   * 查询操作
   */
  val p_vpn2  = Input(UInt(19.W))
  val p_asid  = Input(UInt(8.W))
  val p_index = Output(UInt(log2Up(TLBNUM).W)) // TLB表项id
  val p_find  = Output(Bool())

  val tlbp_onehot = Wire(Vec(TLBNUM, Bool()))
  val tlbp_index  = Wire(UInt(log2Up(TLBNUM).W))
  tlbp_index := OHToUInt(tlbp_onehot.asUInt())
  for (i <- 0 until TLBNUM) {
    tlbp_onehot(i) := tlbrow(i).vpn2 === io.tlb_inst_io.tlbp_io.p_vpn2 &&
      (tlbrow(i).g.asBool() || tlbrow(i).asid === io.tlb_inst_io.tlbp_io.p_asid)
  }
  io.tlb_inst_io.tlbp_io.p_find := tlbp_onehot.asUInt() =/= 0.U
  io.tlb_inst_io.tlbp_io.p_index := tlbp_index
}

object TLB extends App {
  new ChiselStage execute(args, Seq(ChiselGeneratorAnnotation(
    () =>
      new TLB(16))))
}


