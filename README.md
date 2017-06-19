# Huffman11
This project is an incomplete reconstruction of the Huffman dictionaries used to compress version 11.x Intel ME modules. The dictionaries are sufficient to decompress some modules entirely, with hashes matching those in the metadata files, but for others there will typically be a few unknown symbols encountered. Note that the decompression script requires the decompressed size of the Huffman compressed module as an argument; this can be found in the corresponding metadata file for the module.
