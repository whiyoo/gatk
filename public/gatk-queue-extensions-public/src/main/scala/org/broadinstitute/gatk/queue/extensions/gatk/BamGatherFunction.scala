/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.gatk.queue.extensions.gatk

import org.broadinstitute.gatk.queue.function.scattergather.GatherFunction
import org.broadinstitute.gatk.queue.extensions.picard.MergeSamFiles
import org.broadinstitute.gatk.queue.function.RetryMemoryLimit
import org.broadinstitute.gatk.engine.io.stubs.SAMFileWriterArgumentTypeDescriptor
import org.broadinstitute.gatk.queue.util.ClassFieldCache
import java.io.File

/**
 * Merges BAM files using htsjdk.samtools.MergeSamFiles.
 */
class BamGatherFunction extends MergeSamFiles with GatherFunction with RetryMemoryLimit {
  this.assumeSorted = Some(true)

  override def freezeFieldValues() {
    this.input = this.gatherParts
    this.output = this.originalOutput
    //Left to its own devices (ie, MergeSamFiles.freezeFieldValues), outputIndex
    //will be in the gather directory.  Ensure that it actually matches this.output
    if (output != null)
      outputIndex = new File(output.getParentFile, output.getName.stripSuffix(".bam") + ".bai")
    
    val originalGATK = originalFunction.asInstanceOf[CommandLineGATK]

    // Whatever the original function can handle, merging *should* do less.
    this.memoryLimit = originalFunction.memoryLimit

    // bam_compression and index_output_bam_on_the_fly from SAMFileWriterArgumentTypeDescriptor
    // are added by the GATKExtensionsGenerator to the subclass of CommandLineGATK

    val compression = ClassFieldCache.findField(originalFunction.getClass, SAMFileWriterArgumentTypeDescriptor.COMPRESSION_FULLNAME)
    this.compressionLevel = originalGATK.getFieldValue(compression).asInstanceOf[Option[Int]]

    val disableIndex = ClassFieldCache.findField(originalFunction.getClass, SAMFileWriterArgumentTypeDescriptor.DISABLE_INDEXING_FULLNAME)
    this.createIndex = Some(!originalGATK.getFieldValue(disableIndex).asInstanceOf[Boolean])

    val enableMD5 = ClassFieldCache.findField(originalFunction.getClass, SAMFileWriterArgumentTypeDescriptor.ENABLE_MD5_FULLNAME)
    this.createMD5 = Some(originalGATK.getFieldValue(enableMD5).asInstanceOf[Boolean])

    super.freezeFieldValues()
  }
}
