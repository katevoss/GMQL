package it.polimi.genomics.core.DataStructures.RegionAggregate

import it.polimi.genomics.core.{GDouble, GValue}


trait RENode extends Serializable
case class RESTART() extends RENode {override def toString() = "start"}
case class RESTOP() extends RENode {override def toString() = "stop"}
case class RELEFT() extends RENode{override def toString() = "left"}
case class RERIGHT()extends RENode{override def toString() = "right"}
case class RECHR()  extends RENode{override def toString() = "chr"}
case class RESTRAND() extends RENode{override def toString() = "strand"}
case class REPos(position : Int) extends RENode {override def toString() = "position" + position}
case class REFloat(const : Double) extends RENode{override def toString() = "float" + const}
case class REInt(const : Int) extends RENode{override def toString() = "int" + const}
case class READD(o1:RENode, o2:RENode)extends RENode {override def toString() = "add(" + o1 + "," + o2 +")"}
case class RESUB(o1:RENode, o2:RENode) extends RENode{override def toString() = "sub(" + o1 + "," + o2 +")"}
case class REMUL(o1:RENode, o2:RENode) extends RENode{override def toString() = "mul(" + o1 + "," + o2 +")"}
case class REDIV(o1:RENode, o2:RENode) extends RENode{override def toString() = "div(" + o1 + "," + o2 +")"}


object COORD_POS {
  val CHR_POS = -1
  val LEFT_POS = -10
  val RIGHT_POS = -100
  val STRAND_POS = -1000
  val START_POS = -10000
  val STOP_POS = -100000
}

trait RegionFunction extends Serializable {
  val inputIndexes : List[Int]
  def output_index : Option[Int] = None
  def output_name : Option[String] = None
}

trait   RegionExtension extends RegionFunction {
  val fun : Array[GValue] => GValue
}


