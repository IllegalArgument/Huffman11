import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, Paths}

object HuffmanDecompress {
	import HuffmanDictionaries.Dictionaries

	val ChunkSize = 0x1000

	def codeLength(code: Int): Int = {
		if ((code >>> 25) >= 0x77) {
			7
		} else if ((code >>> 24) >= 0xA3) {
			8
		} else if ((code >>> 23) >= 0xBC) {
			9
		} else if ((code >>> 22) >= 0xCB) {
			10
		} else if ((code >>> 21) >= 0xD8) {
			11
		} else if ((code >>> 20) >= 0xDF) {
			12
		} else if ((code >>> 19) >= 0xCA) {
			13
		} else if ((code >>> 18) >= 0x50) {
			14
		} else {
			15
		}
	}

	def decompress(file: Path, decompressedSize: Int): Array[Byte] = {
		val fileBytes = Files.readAllBytes(file)
		val chunkCount = decompressedSize / ChunkSize
		val headerSize = 0x4 * chunkCount
		val headerBuffer = ByteBuffer.wrap(fileBytes.take(headerSize)).order(ByteOrder.LITTLE_ENDIAN)
		val compressedBuffer = ByteBuffer.wrap(fileBytes.drop(headerSize)).order(ByteOrder.BIG_ENDIAN)
		val decompressedBuffer = ByteBuffer.allocate(decompressedSize).order(ByteOrder.BIG_ENDIAN)
		val chunks = List.fill(chunkCount)({
			val entry = headerBuffer.getInt()
			(entry & 0x1FFFFFF, (entry >>> 25) & 0x7F)
		})
		chunks.zip(chunks.tail.map(_._1) :+ compressedBuffer.remaining).zipWithIndex.foreach({
			case (((startOffset, dictionaryType), endOffset), index) =>
				println(f"==Processing chunk 0x$index%X at compressed offset 0x$startOffset%X with dictionary 0x$dictionaryType%X==")
				val dictionary = Dictionaries(dictionaryType)
				compressedBuffer.position(startOffset).limit(endOffset)
				decompressedBuffer.position(index * ChunkSize).limit((index + 1) * ChunkSize)
				var bitBuffer = 0
				var availableBits = 0
				while (decompressedBuffer.hasRemaining) {
					// Fill the bit buffer
					while (availableBits <= 24 && compressedBuffer.hasRemaining) {
						bitBuffer |= (compressedBuffer.get() & 0xFF) << (24 - availableBits)
						availableBits += 8
					}

					// Calculate the length of the next codeword
					val length = codeLength(bitBuffer)
					if (availableBits >= length) {
						// If we have enough bits buffered, extract the codeword and update the bit buffer
						val code = (bitBuffer >>> (32 - length)) & 0xFFFF
						bitBuffer <<= length
						availableBits -= length

						val symbol = dictionary(length).getOrElse(code, {
							println(f"Unknown code ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')} (dictionary 0x$dictionaryType%X, length $length, code 0x$code%X) at decompressed offset 0x${decompressedBuffer.position}%X")
							Array.fill(1)(0x7F.toByte)
						})
						if (decompressedBuffer.remaining >= symbol.length) {
							// If there's stilll space in the decompressed buffer for this chunk, just write the symbol
							decompressedBuffer.put(symbol)
						} else {
							// Although this shouldn't happen with complete dictionaries, since we have a few missing
							// symbols, it's possible that a codeword is parsed that would map to a symbol that is
							// longer than the space remaining in the decompressed chunk buffer
							// If this happens, ignore that last symbol that would otherwise overflow the chunk buffer
							println(f"Ignoring unaligned final code ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')} (dictionary 0x$dictionaryType%X, length $length, code 0x$code%X) at decompressed offset 0x${decompressedBuffer.position}%X")
							decompressedBuffer.put(Array.fill(decompressedBuffer.remaining)(0x7F.toByte))
						}
					} else {
						// Since we always try to fill the bit buffer as much as possible before reading the next
						// codeword, if we read a codeword that requires more bits than we have buffered, we know that
						// we've reached the end of the compressed stream
						println(f"Reached end of compressed stream early at decompressed offset 0x${decompressedBuffer.position}%X")
						decompressedBuffer.put(Array.fill(decompressedBuffer.remaining)(0x7F.toByte))
					}
				}
		})
		decompressedBuffer.array()
	}

	def main(args: Array[String]): Unit = {
		//TODO: Parse the compressed/decompressed sizes from the metadata file if given as an argument
		if (args.length == 3) {
			val compressedFile = Paths.get(args(0))
			val decompressedFile = Paths.get(args(1))
			val decompressedSize = Integer.decode(args(2))
			val decompressed = decompress(compressedFile, decompressedSize)
			Files.write(decompressedFile, decompressed)
		} else {
			println("Usage: HuffmanDecompress <compressed file path> <decompressed file path> <decompressed file size>")
			println("Example: HuffmanDecompress bup.huff bup.mod 0x31000")
		}
	}
}
