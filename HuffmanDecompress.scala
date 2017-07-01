import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, Paths}

object HuffmanDecompress {
	import HuffmanDictionaries.Shape
	import HuffmanDictionaries.Symbols
	import HuffmanDictionaries.UnknownCodes

	val ChunkSize = 0x1000

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

				val dictionary = Symbols(dictionaryType)
				val unknown = UnknownCodes(dictionaryType)

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

					// Look up the length of the codeword and the highest value codeword with that length
					val (length, _, firstCode) = Shape.dropWhile(x => Integer.compareUnsigned(bitBuffer, x._2) < 0).head

					if (availableBits >= length) {
						// If we have enough bits buffered, extract the codeword and update the bit buffer
						val code = bitBuffer >>> (32 - length)
						bitBuffer <<= length
						availableBits -= length

						val symbol = dictionary(length)(firstCode - code)
						if (decompressedBuffer.remaining >= symbol.length) {
							// If there's stilll space in the decompressed buffer for this chunk, just write the symbol
							// And print some information if a filler symbol was used
							if (unknown(length).contains(code)) {
								println(f"Unknown codeword ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')}%15s (dictionary 0x$dictionaryType%X, code length $length%2s, code ${f"0x$code%X"}%5s, symbol length ${symbol.length}) at decompressed offset 0x${decompressedBuffer.position}%X")
							}
							decompressedBuffer.put(symbol)
						} else {
							// Although this shouldn't happen with complete dictionaries, since we have a few missing
							// symbols, it's possible that a codeword is parsed that would map to a symbol that is
							// longer than the space remaining in the decompressed chunk buffer
							// If this happens, ignore that last symbol that would otherwise overflow the chunk buffer
							println(f"Skipping overflowing symbol ${s"%${length}s".format(code.toBinaryString).replace(' ', '0')}%15s (dictionary 0x$dictionaryType%X, code length $length%2s, code ${f"0x$code%X"}%5s, symbol length ${symbol.length}) at decompressed offset 0x${decompressedBuffer.position}%X")
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
