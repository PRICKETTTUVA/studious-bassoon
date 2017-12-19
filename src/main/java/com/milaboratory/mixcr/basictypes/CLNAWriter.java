/*
 * Copyright (c) 2014-2017, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.basictypes;

import cc.redberry.pipe.CUtils;
import cc.redberry.pipe.OutputPort;
import cc.redberry.pipe.OutputPortCloseable;
import com.milaboratory.primitivio.PrimitivO;
import com.milaboratory.util.CanReportProgress;
import com.milaboratory.util.Sorter;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.array.TLongArrayList;
import io.repseq.core.GeneFeature;
import org.apache.commons.io.output.CountingOutputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public final class CLNAWriter implements AutoCloseable, CanReportProgress {
    static final String MAGIC_V1 = "MiXCR.CLNA.V01";
    static final String MAGIC = MAGIC_V1;
    static final int MAGIC_LENGTH = 14;
    private final File tempFile;
    private final CountingOutputStream outputStream;
    private final PrimitivO output;
    private volatile int numberOfClones = -1;
    private volatile OutputPortCloseable<VDJCAlignments> sortedAligtnments = null;
    private volatile long numberOfAlignments = -1, numberOfAlignmentsWritten = 0;
    private volatile boolean finished = false;

    public CLNAWriter(File file) throws IOException {
        this.tempFile = new File(file.getAbsolutePath() + ".unsorted");
        this.outputStream = new CountingOutputStream(new BufferedOutputStream(
                new FileOutputStream(file), 131072));
        this.outputStream.write(MAGIC.getBytes(StandardCharsets.UTF_8));
        this.output = new PrimitivO(this.outputStream);
    }

    /**
     * Step 1
     */
    public synchronized void writeClones(CloneSet cloneSet) {
        GeneFeature[] assemblingFeatures = cloneSet.getAssemblingFeatures();
        output.writeObject(assemblingFeatures);
        IO.writeGT2GFMap(output, cloneSet.alignedFeatures);

        IOUtil.writeGeneReferences(output, cloneSet.getUsedGenes(),
                new CloneSetIO.GT2GFAdapter(cloneSet.alignedFeatures));

        output.writeInt(cloneSet.getClones().size());

        for (Clone clone : cloneSet)
            output.writeObject(clone);

        numberOfClones = cloneSet.size();
    }

    /**
     * Step 2
     */
    public synchronized void sortAlignments(OutputPort<VDJCAlignments> alignments, long numberOfAlignments) throws IOException {
        if (numberOfClones == -1)
            throw new IllegalStateException("Write clones before writing alignments.");

        this.numberOfAlignments = numberOfAlignments;
        int chunkSize = (int) Math.min(Math.max(16384, numberOfAlignments / 8), 1048576);

        sortedAligtnments = Sorter.sort(alignments,
                new Comparator<VDJCAlignments>() {
                    @Override
                    public int compare(VDJCAlignments o1, VDJCAlignments o2) {
                        int i = Integer.compare(o1.cloneIndex, o2.cloneIndex);
                        if (i != 0)
                            return i;
                        return Byte.compare(o1.mappingType, o2.mappingType);
                    }
                },
                chunkSize,
                VDJCAlignments.class,
                tempFile);
    }

    /**
     * Step 3
     */
    public synchronized void writeAlignmentsAndIndex() {
        TLongArrayList index = new TLongArrayList();

        index.add(outputStream.getByteCount());

        int currentCloneIndex = -1;

        for (VDJCAlignments alignments : CUtils.it(sortedAligtnments)) {
            if (currentCloneIndex != alignments.cloneIndex) {
                ++currentCloneIndex;
                if (currentCloneIndex != alignments.cloneIndex)
                    throw new IllegalArgumentException("No alignments for clone number " + currentCloneIndex);
                if (alignments.cloneIndex >= numberOfClones)
                    throw new IllegalArgumentException("Out of range clone Index in alignment: " + currentCloneIndex);
                index.add(outputStream.getByteCount());
            }
            output.writeObject(alignments);
            ++numberOfAlignmentsWritten;
        }

        long indexBeginOffset = outputStream.getByteCount();
        long previousValue = 0;
        TLongIterator iterator = index.iterator();
        while (iterator.hasNext()) {
            long value = iterator.next();
            output.writeVarLong(value - previousValue);
            previousValue = value;
        }

        output.writeLong(indexBeginOffset);

        finished = true;
    }

    @Override
    public double getProgress() {
        return 1.0 * numberOfAlignmentsWritten / numberOfAlignments;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void close() throws Exception {
        output.close();
    }
}
