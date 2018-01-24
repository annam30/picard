/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package picard.sam;

import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMUtils;
import htsjdk.samtools.SAMValidationError;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Lazy;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.samtools.util.StringUtil;
import htsjdk.samtools.util.TrimmingUtil;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.ReadDataManipulationProgramGroup;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p> Extracts read sequences and qualities from the input SAM/BAM file and writes them into
 * the output file in Sanger FASTQ format.  .
 * See <a href="http://maq.sourceforge.net/fastq.shtml">MAQ FASTQ specification</a> for details.
 * This tool can be used by way of a pipe to run BWA MEM on unmapped BAM (uBAM) files efficiently.</p>
 * <p>In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome,
 * the read's sequence from input sam file will be reverse-complemented prior to writing it to FASTQ in order restore correctly
 * the original read sequence as it was generated by the sequencer.</p>
 * <br />
 " <h4>Usage example:</h4>" +
 " <pre>" +
 " java -jar picard.jar SamToFastq \\<br />" +
 "     I=input.bam \\<br />" +
 "     FASTQ=output.fastq" +
 " </pre>" +
 " <hr />"
 */
@CommandLineProgramProperties(
        summary = SamToFastq.USAGE_SUMMARY + SamToFastq.USAGE_DETAILS,
        oneLineSummary = SamToFastq.USAGE_SUMMARY,
        programGroup = ReadDataManipulationProgramGroup.class)
@DocumentedFeature
public class SamToFastq extends CommandLineProgram {
    static final String USAGE_SUMMARY = "Converts a SAM or BAM file to FASTQ.";
    static final String USAGE_DETAILS = "Extracts read sequences and qualities from the input SAM/BAM file and writes them into" +
            "the output file in Sanger FASTQ format." +
            "See <a href=\"http://maq.sourceforge.net/fastq.shtml\">MAQ FASTQ specification</a> for details." +
            "This tool can be used by way of a pipe to run BWA MEM on unmapped BAM (uBAM) files efficiently.</p>" +
            "<p>In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome," +
            "the read's sequence from input sam file will be reverse-complemented prior to writing it to FASTQ in order restore correctly" +
            "the original read sequence as it was generated by the sequencer.</p>" +
            "<br />" +
            "<h4>Usage example:</h4>" +
            "<pre>" +
            "java -jar picard.jar SamToFastq <br />" +
            "     I=input.bam<br />" +
            "     FASTQ=output.fastq" +
            "</pre>" +
            "<hr />";
    @Argument(doc = "Input SAM/BAM file to extract reads from", shortName = StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT;

    @Argument(shortName = "F", doc = "Output FASTQ file (single-end fastq or, if paired, first end of the pair FASTQ).",
            mutex = {"OUTPUT_PER_RG", "COMPRESS_OUTPUTS_PER_RG"})
    public File FASTQ;

    @Argument(shortName = "F2", doc = "Output FASTQ file (if paired, second end of the pair FASTQ).", optional = true,
            mutex = {"OUTPUT_PER_RG", "COMPRESS_OUTPUTS_PER_RG"})
    public File SECOND_END_FASTQ;

    @Argument(shortName = "FU", doc = "Output FASTQ file for unpaired reads; may only be provided in paired-FASTQ mode", optional = true,
            mutex = {"OUTPUT_PER_RG", "COMPRESS_OUTPUTS_PER_RG"})
    public File UNPAIRED_FASTQ;

    @Argument(shortName = "OPRG", doc = "Output a FASTQ file per read group (two FASTQ files per read group if the group is paired).",
            optional = true, mutex = {"FASTQ", "SECOND_END_FASTQ", "UNPAIRED_FASTQ"})
    public boolean OUTPUT_PER_RG;

    @Argument(shortName = "GZOPRG", doc = "Compress output FASTQ files per read group using gzip and append a .gz extension to the file names.",
            mutex = {"FASTQ", "SECOND_END_FASTQ", "UNPAIRED_FASTQ"})
    public Boolean COMPRESS_OUTPUTS_PER_RG = false;

    @Argument(shortName="RGT", doc = "The read group tag (PU or ID) to be used to output a FASTQ file per read group.")
    public String RG_TAG = "PU";

    @Argument(shortName = "ODIR", doc = "Directory in which to output the FASTQ file(s).  Used only when OUTPUT_PER_RG is true.",
            optional = true)
    public File OUTPUT_DIR;

    @Argument(shortName = "RC", doc = "Re-reverse bases and qualities of reads with negative strand flag set before writing them to FASTQ",
            optional = true)
    public boolean RE_REVERSE = true;

    @Argument(shortName = "INTER", doc = "Will generate an interleaved fastq if paired, each line will have /1 or /2 to describe which end it came from")
    public boolean INTERLEAVE = false;

    @Argument(shortName = "NON_PF", doc = "Include non-PF reads from the SAM file into the output " +
            "FASTQ files. PF means 'passes filtering'. Reads whose 'not passing quality controls' " +
            "flag is set are non-PF reads. See GATK Dictionary for more info.")
    public boolean INCLUDE_NON_PF_READS = false;

    @Argument(shortName = "CLIP_ATTR", doc = "The attribute that stores the position at which " +
            "the SAM record should be clipped", optional = true)
    public String CLIPPING_ATTRIBUTE;

    @Argument(shortName = "CLIP_ACT", doc = "The action that should be taken with clipped reads: " +
            "'X' means the reads and qualities should be trimmed at the clipped position; " +
            "'N' means the bases should be changed to Ns in the clipped region; and any " +
            "integer means that the base qualities should be set to that value in the " +
            "clipped region.", optional = true)
    public String CLIPPING_ACTION;

    @Argument(shortName = "CLIP_MIN", doc = "When performing clipping with the CLIPPING_ATTRIBUTE and CLIPPING_ACTION " +
            "parameters, ensure that the resulting reads after clipping are at least CLIPPING_MIN_LENGTH bases long. " +
            "If the original read is shorter than CLIPPING_MIN_LENGTH then the original read length will be maintained.")
    public int CLIPPING_MIN_LENGTH = 0;

    @Argument(shortName = "R1_TRIM", doc = "The number of bases to trim from the beginning of read 1.")
    public int READ1_TRIM = 0;

    @Argument(shortName = "R1_MAX_BASES", doc = "The maximum number of bases to write from read 1 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional = true)
    public Integer READ1_MAX_BASES_TO_WRITE;

    @Argument(shortName = "R2_TRIM", doc = "The number of bases to trim from the beginning of read 2.")
    public int READ2_TRIM = 0;

    @Argument(shortName = "R2_MAX_BASES", doc = "The maximum number of bases to write from read 2 after trimming. " +
            "If there are fewer than this many bases left after trimming, all will be written.  If this " +
            "value is null then all bases left after trimming will be written.", optional = true)
    public Integer READ2_MAX_BASES_TO_WRITE;

    @Argument(shortName="Q", doc="End-trim reads using the phred/bwa quality trimming algorithm and this quality.", optional = true)
    public Integer QUALITY;

    @Argument(doc = "If true, include non-primary alignments in the output.  Support of non-primary alignments in SamToFastq " +
            "is not comprehensive, so there may be exceptions if this is set to true and there are paired reads with non-primary alignments.")
    public boolean INCLUDE_NON_PRIMARY_ALIGNMENTS = false;

    @Argument(shortName = "STG", doc = "List of comma separated tag values to extract from Input SAM/BAM to be used as read sequence", optional = true)
    public List<String> SEQUENCE_TAG_GROUP;

    @Argument(shortName = "QTG", doc = "List of comma separated tag values to extract from Input SAM/BAM to be used as read qualities")
    public List<String> QUALITY_TAG_GROUP;

    @Argument(shortName = "SEP", doc = "List of sequences to put in between each comma separated list of sequence tags (QFT)")
    public List<String> TAG_GROUP_SEPERATOR;

    @Argument(shortName = "GZOPTG", doc = "Compress output FASTQ files per Tag grouping using gzip and append a .gz extension to the file names.")
    public Boolean COMPRESS_OUTPUTS_PER_TAG_GROUP = false;

    private final Log log = Log.getInstance(SamToFastq.class);

    private final static String TAG_SPLIT_DEFAULT_SEP = "";
    private final static String TAG_SPLIT_DEFAULT_QUAL = "~";

    private ArrayList<String[]> SPLIT_SEQUENCE_TAGS;
    private ArrayList<String[]> SPLIT_QUALITY_TAGS;
    private ArrayList<String> SPLIT_SEPARATOR_TAGS;

    public static void main(final String[] argv) {
        System.exit(new SamToFastq().instanceMain(argv));
    }

    protected int doWork() {
        IOUtil.assertFileIsReadable(INPUT);
        final SamReader reader = SamReaderFactory.makeDefault().referenceSequence(REFERENCE_SEQUENCE).open(INPUT);
        final Map<String, SAMRecord> firstSeenMates = new HashMap<>();
        final FastqWriterFactory factory = new FastqWriterFactory();
        factory.setCreateMd5(CREATE_MD5_FILE);
        final Map<SAMReadGroupRecord, FastqWriters> writers = generateWriters(reader.getFileHeader().getReadGroups(), factory);
        if (writers.isEmpty()) {
            final String msgBase = INPUT + " does not contain Read Groups";
            final String msg = OUTPUT_PER_RG ? msgBase + ", consider not using the OUTPUT_PER_RG option" : msgBase;
            throw new PicardException(msg);
        }

        final ProgressLogger progress = new ProgressLogger(log);
        setupTagSplitValues();
        for (final SAMRecord currentRecord : reader) {
            if (currentRecord.isSecondaryOrSupplementary() && !INCLUDE_NON_PRIMARY_ALIGNMENTS)
                continue;

            // Skip non-PF reads as necessary
            if (currentRecord.getReadFailsVendorQualityCheckFlag() && !INCLUDE_NON_PF_READS)
                continue;

            final FastqWriters fq = writers.get(currentRecord.getReadGroup());
            if (currentRecord.getReadPairedFlag()) {
                final String currentReadName = currentRecord.getReadName();
                final SAMRecord firstRecord = firstSeenMates.remove(currentReadName);
                if (firstRecord == null) {
                    firstSeenMates.put(currentReadName, currentRecord);
                } else {
                    assertPairedMates(firstRecord, currentRecord);

                    final SAMRecord read1 =
                            currentRecord.getFirstOfPairFlag() ? currentRecord : firstRecord;
                    final SAMRecord read2 =
                            currentRecord.getFirstOfPairFlag() ? firstRecord : currentRecord;
                    writeRecord(read1, 1, fq.getFirstOfPair(), READ1_TRIM, READ1_MAX_BASES_TO_WRITE);
                    writeTagRecords(read1, 1, fq.tagWriters);
                    final FastqWriter secondOfPairWriter = fq.getSecondOfPair();
                    if (secondOfPairWriter == null) {
                        throw new PicardException("Input contains paired reads but no SECOND_END_FASTQ specified.");
                    }
                    writeRecord(read2, 2, secondOfPairWriter, READ2_TRIM, READ2_MAX_BASES_TO_WRITE);
                    writeTagRecords(read2, 2, fq.tagWriters);
                }
            } else {
                writeRecord(currentRecord, null, fq.getUnpaired(), READ1_TRIM, READ1_MAX_BASES_TO_WRITE);
                writeTagRecords(currentRecord, null, fq.tagWriters);
            }

            progress.record(currentRecord);
        }

        CloserUtil.close(reader);

        // Close all the fastq writers being careful to close each one only once!
        for (final FastqWriters writerMapping : new HashSet<>(writers.values())) {
            writerMapping.closeAll();
        }

        if (!firstSeenMates.isEmpty()) {
            SAMUtils.processValidationError(new SAMValidationError(SAMValidationError.Type.MATE_NOT_FOUND,
                    "Found " + firstSeenMates.size() + " unpaired mates", null), VALIDATION_STRINGENCY);
        }

        return 0;
    }

    /**
     * Generates the writers for the given read groups or, if we are not emitting per-read-group, just returns the single set of writers.
     */
    private Map<SAMReadGroupRecord, FastqWriters> generateWriters(final List<SAMReadGroupRecord> samReadGroupRecords,
                                                                  final FastqWriterFactory factory) {

        final Map<SAMReadGroupRecord, FastqWriters> writerMap = new HashMap<>();

        final FastqWriters fastqWriters;
        if (!OUTPUT_PER_RG) {
            IOUtil.assertFileIsWritable(FASTQ);
            final FastqWriter firstOfPairWriter = factory.newWriter(FASTQ);

            final FastqWriter secondOfPairWriter;
            if (INTERLEAVE) {
                secondOfPairWriter = firstOfPairWriter;
            } else if (SECOND_END_FASTQ != null) {
                IOUtil.assertFileIsWritable(SECOND_END_FASTQ);
                secondOfPairWriter = factory.newWriter(SECOND_END_FASTQ);
            } else {
                secondOfPairWriter = null;
            }

            /* Prepare the writer that will accept unpaired reads.  If we're emitting a single fastq - and assuming single-ended reads -
             * then this is simply that one fastq writer.  Otherwise, if we're doing paired-end, we emit to a third new writer, since
             * the other two fastqs are accepting only paired end reads. */
            final FastqWriter unpairedWriter = UNPAIRED_FASTQ == null ? firstOfPairWriter : factory.newWriter(UNPAIRED_FASTQ);

            /* Prepare tag writers if tag groupings are provided to the tool */
            if (SEQUENCE_TAG_GROUP.isEmpty()){
                fastqWriters = new FastqWriters(firstOfPairWriter, secondOfPairWriter, unpairedWriter);
            } else {
                final List<FastqWriter> tagFiles = makeTagWriters(null).stream().map(factory::newWriter).collect(Collectors.toList());
                fastqWriters = new FastqWriters(firstOfPairWriter, secondOfPairWriter, unpairedWriter, tagFiles);
            }

            // For all read groups we may find in the bam, register this single set of writers for them.
            writerMap.put(null, fastqWriters);
            for (final SAMReadGroupRecord rg : samReadGroupRecords) {
                writerMap.put(rg, fastqWriters);
            }
        } else {
            // When we're creating a fastq-group per readgroup, by convention we do not emit a special fastq for unpaired reads.
            for (final SAMReadGroupRecord rg : samReadGroupRecords) {
                final FastqWriter firstOfPairWriter = factory.newWriter(makeReadGroupFile(rg, "_1"));
                // Create this writer on-the-fly; if we find no second-of-pair reads, don't bother making a writer (or delegating,
                // if we're interleaving).
                final Lazy<FastqWriter> lazySecondOfPairWriter = new Lazy<>(() -> INTERLEAVE ? firstOfPairWriter : factory.newWriter(makeReadGroupFile(rg, "_2")));

                List<FastqWriter> tagWriters = null;
                /* Prepare tag writers if tag groupings are provided to the tool */
                if (!SEQUENCE_TAG_GROUP.isEmpty()){
                    tagWriters = makeTagWriters(rg).stream().map(factory::newWriter).collect(Collectors.toList());
                }

                writerMap.put(rg, new FastqWriters(firstOfPairWriter, lazySecondOfPairWriter, firstOfPairWriter, tagWriters));
            }
        }
        return writerMap;
    }

    private File makeReadGroupFile(final SAMReadGroupRecord readGroup, final String preExtSuffix) {
        String fileName = null;
        if (RG_TAG.equalsIgnoreCase("PU")){
            fileName = readGroup.getPlatformUnit();
        } else if (RG_TAG.equalsIgnoreCase("ID")){
            fileName = readGroup.getReadGroupId();
        }
        if (fileName == null) {
            throw new PicardException("The selected RG_TAG: "+RG_TAG+" is not present in the bam header.");
        }
        fileName = IOUtil.makeFileNameSafe(fileName);
        if (preExtSuffix != null) fileName += preExtSuffix;
        fileName += COMPRESS_OUTPUTS_PER_RG ? ".fastq.gz" : ".fastq";

        final File result = (OUTPUT_DIR != null)
                ? new File(OUTPUT_DIR, fileName)
                : new File(fileName);
        IOUtil.assertFileIsWritable(result);
        return result;
    }

    private List<File> makeTagWriters(final SAMReadGroupRecord readGroup) {
        if (SEQUENCE_TAG_GROUP.isEmpty()){
            return null;
        }
        String baseFilename = null;
        if (readGroup != null) {
            if (RG_TAG.equalsIgnoreCase("PU")) {
                baseFilename = readGroup.getPlatformUnit() + "_";
            } else if (RG_TAG.equalsIgnoreCase("ID")) {
                baseFilename = readGroup.getReadGroupId() + "_";
            }
            if (baseFilename == null) {
                throw new PicardException("The selected RG_TAG: " + RG_TAG + " is not present in the bam header.");
            }
        } else {
            baseFilename = "";
        }
        List<File> tagFiles = new ArrayList<>();
        for (String tagSplit : SEQUENCE_TAG_GROUP) {
            String fileName = baseFilename;

            fileName += tagSplit.replace(",", "_");
            fileName = IOUtil.makeFileNameSafe(fileName);

            fileName += COMPRESS_OUTPUTS_PER_TAG_GROUP ? ".fastq.gz" : ".fastq";

            final File result = (OUTPUT_DIR != null)
                    ? new File(OUTPUT_DIR, fileName)
                    : new File(fileName);
            IOUtil.assertFileIsWritable(result);
            tagFiles.add(result);
        }
        return tagFiles;
    }

    private void writeRecord(final SAMRecord read, final Integer mateNumber, final FastqWriter writer,
                     final int basesToTrim, final Integer maxBasesToWrite) {
        final String seqHeader = mateNumber == null ? read.getReadName() : read.getReadName() + "/" + mateNumber;
        String readString = read.getReadString();
        String baseQualities = read.getBaseQualityString();

        // If we're clipping, do the right thing to the bases or qualities
        if (CLIPPING_ATTRIBUTE != null) {
            Integer clipPoint = (Integer) read.getAttribute(CLIPPING_ATTRIBUTE);
            if (clipPoint != null && clipPoint < CLIPPING_MIN_LENGTH) {
                clipPoint = Math.min(readString.length(), CLIPPING_MIN_LENGTH);
            }

            if (clipPoint != null) {
                if (CLIPPING_ACTION.equalsIgnoreCase("X")) {
                    readString = clip(readString, clipPoint, null, !read.getReadNegativeStrandFlag());
                    baseQualities = clip(baseQualities, clipPoint, null, !read.getReadNegativeStrandFlag());
                }
                else if (CLIPPING_ACTION.equalsIgnoreCase("N")) {
                    readString = clip(readString, clipPoint, 'N', !read.getReadNegativeStrandFlag());
                }
                else {
                    final char newQual = SAMUtils.phredToFastq(new byte[]{(byte) Integer.parseInt(CLIPPING_ACTION)}).charAt(0);
                    baseQualities = clip(baseQualities, clipPoint, newQual, !read.getReadNegativeStrandFlag());
                }
            }
        }

        if (RE_REVERSE && read.getReadNegativeStrandFlag()) {
            readString = SequenceUtil.reverseComplement(readString);
            baseQualities = StringUtil.reverseString(baseQualities);
        }

        if (basesToTrim > 0) {
            readString = readString.substring(basesToTrim);
            baseQualities = baseQualities.substring(basesToTrim);
        }

        // Perform quality trimming if desired, making sure to leave at least one base!
        if (QUALITY != null) {
            final byte[] quals = SAMUtils.fastqToPhred(baseQualities);
            final int qualityTrimIndex = Math.max(1, TrimmingUtil.findQualityTrimPoint(quals, QUALITY));
            if (qualityTrimIndex < quals.length) {
                readString    = readString.substring(0, qualityTrimIndex);
                baseQualities = baseQualities.substring(0, qualityTrimIndex);
            }
        }

        if (maxBasesToWrite != null && maxBasesToWrite < readString.length()) {
            readString = readString.substring(0, maxBasesToWrite);
            baseQualities = baseQualities.substring(0, maxBasesToWrite);
        }

        writer.write(new FastqRecord(seqHeader, readString, "", baseQualities));

    }

    private void writeTagRecords (final SAMRecord read, final Integer mateNumber, final List<FastqWriter> tagWriters){
        if (SEQUENCE_TAG_GROUP.isEmpty()) return;

        final String seqHeader = mateNumber == null ? read.getReadName() : read.getReadName() + "/" + mateNumber;

        for (int i = 0; i < SEQUENCE_TAG_GROUP.size(); i ++){
            final String tmpTagSep = SPLIT_SEPARATOR_TAGS.get(i);
            final String[] sequenceTagsToWrite = SPLIT_SEQUENCE_TAGS.get(i);
            final String newSequence = String.join(tmpTagSep, Arrays.stream(sequenceTagsToWrite)
                    .map(read::getStringAttribute)
                    .collect(Collectors.toList()));

            final String tmpQualSep = StringUtils.repeat(TAG_SPLIT_DEFAULT_QUAL, tmpTagSep.length());
            final String[] qualityTagsToWrite = SPLIT_QUALITY_TAGS.get(i);
            final String newQual = QUALITY_TAG_GROUP.isEmpty() ? StringUtils.repeat(TAG_SPLIT_DEFAULT_QUAL, newSequence.length()):
                    String.join(tmpQualSep, Arrays.stream(qualityTagsToWrite)
                    .map(read::getStringAttribute)
                    .collect(Collectors.toList()));
            FastqWriter writer = tagWriters.get(i);
            writer.write(new FastqRecord(seqHeader, newSequence, "", newQual));
        }

    }

    // Setting up the Groupings of Sequence Tags, Quality Tags, and Separator Strings so we dont have to calculate them for every loop
    private void setupTagSplitValues() {
        if (SEQUENCE_TAG_GROUP.isEmpty()) return;

        SPLIT_SEQUENCE_TAGS = new ArrayList<>();
        SPLIT_QUALITY_TAGS = new ArrayList<>();
        SPLIT_SEPARATOR_TAGS = new ArrayList<>();

        for (int i = 0; i < SEQUENCE_TAG_GROUP.size(); i ++){
            SPLIT_SEQUENCE_TAGS.add(SEQUENCE_TAG_GROUP.get(i).trim().split(","));
            SPLIT_QUALITY_TAGS.add(QUALITY_TAG_GROUP.isEmpty() ? null : QUALITY_TAG_GROUP.get(i).trim().split(","));
            SPLIT_SEPARATOR_TAGS.add(TAG_GROUP_SEPERATOR.isEmpty() ? TAG_SPLIT_DEFAULT_SEP : TAG_GROUP_SEPERATOR.get(i));
        }
    }

    /**
     * Utility method to handle the changes required to the base/quality strings by the clipping
     * parameters.
     *
     * @param src         The string to clip
     * @param point       The 1-based position of the first clipped base in the read
     * @param replacement If non-null, the character to replace in the clipped positions
     *                    in the string (a quality score or 'N').  If null, just trim src
     * @param posStrand   Whether the read is on the positive strand
     * @return String       The clipped read or qualities
     */
    private String clip(final String src, final int point, final Character replacement, final boolean posStrand) {
        final int len = src.length();
        String result = posStrand ? src.substring(0, point - 1) : src.substring(len - point + 1);
        if (replacement != null) {
            if (posStrand) {
                for (int i = point; i <= len; i++) {
                    result += replacement;
                }
            } else {
                for (int i = 0; i <= len - point; i++) {
                    result = replacement + result;
                }
            }
        }
        return result;
    }

    private void assertPairedMates(final SAMRecord record1, final SAMRecord record2) {
        if (!(record1.getFirstOfPairFlag() && record2.getSecondOfPairFlag() ||
                record2.getFirstOfPairFlag() && record1.getSecondOfPairFlag())) {
            throw new PicardException("Illegal mate state: " + record1.getReadName());
        }
    }

    /**
     * Put any custom command-line validation in an override of this method.
     * clp is initialized at this point and can be used to print usage and access argv.
     * Any options set by command-line parser can be validated.
     *
     * @return null if command line is valid.  If command line is invalid, returns an array of error
     * messages to be written to the appropriate place.
     */
    protected String[] customCommandLineValidation() {

        List<String> errors = new ArrayList<>();

        if (INTERLEAVE && SECOND_END_FASTQ != null) {
            errors.add("Cannot set INTERLEAVE to true and pass in a SECOND_END_FASTQ");
        }

        if (UNPAIRED_FASTQ != null && SECOND_END_FASTQ == null) {
            errors.add("UNPAIRED_FASTQ may only be set when also emitting read1 and read2 fastqs (so SECOND_END_FASTQ must also be set).");
        }

        if ((CLIPPING_ATTRIBUTE != null && CLIPPING_ACTION == null) ||
                (CLIPPING_ATTRIBUTE == null && CLIPPING_ACTION != null)) {
            errors.add("Both or neither of CLIPPING_ATTRIBUTE and CLIPPING_ACTION should be set.");
        }

        if (CLIPPING_ACTION != null) {
            if (CLIPPING_ACTION.equals("N") || CLIPPING_ACTION.equals("X")) {
                // Do nothing, this is fine
            } else {
                try {
                    Integer.parseInt(CLIPPING_ACTION);
                } catch (NumberFormatException nfe) {
                    errors.add("CLIPPING ACTION must be one of: N, X, or an integer");
                }
            }
        }

        if ((OUTPUT_PER_RG && OUTPUT_DIR == null) || ((!OUTPUT_PER_RG) && OUTPUT_DIR != null)) {
            errors.add("If OUTPUT_PER_RG is true, then OUTPUT_DIR should be set. If ");

        }

        if (OUTPUT_PER_RG) {
            if (RG_TAG == null) {
                errors.add("If OUTPUT_PER_RG is true, then RG_TAG should be set.");
            } else if (! (RG_TAG.equalsIgnoreCase("PU") || RG_TAG.equalsIgnoreCase("ID")) ){
                errors.add("RG_TAG must be: PU or ID");
            }
        }

        if (!SEQUENCE_TAG_GROUP.isEmpty() && !QUALITY_TAG_GROUP.isEmpty() && SEQUENCE_TAG_GROUP.size() != QUALITY_TAG_GROUP.size()) {
            errors.add("QUALITY_TAG_GROUP size must be equal to SEQUENCE_TAG_GROUP or not specified at all.");
        }

        if (!SEQUENCE_TAG_GROUP.isEmpty() && !TAG_GROUP_SEPERATOR.isEmpty() && SEQUENCE_TAG_GROUP.size() != TAG_GROUP_SEPERATOR.size()) {
            errors.add("TAG_GROUP_SEPERATOR size must be equal to SEQUENCE_TAG_GROUP or not specified at all.");
        }

        if (!errors.isEmpty()) return errors.toArray(new String[errors.size()]);

        return super.customCommandLineValidation();
    }

    /**
     * A collection of {@link htsjdk.samtools.fastq.FastqWriter}s for particular types of reads.
     * <p/>
     * Allows for lazy construction of the second-of-pair writer, since when we are in the "output per read group mode", we only wish to
     * generate a second-of-pair fastq if we encounter a second-of-pair read.
     */
    static final class FastqWriters {
        private final FastqWriter firstOfPair, unpaired;
        private final Lazy<FastqWriter> secondOfPair;
        private final List<FastqWriter> tagWriters;

        /** Simple constructor; all writers are pre-initialized.. */
        private FastqWriters(final FastqWriter firstOfPair, final Lazy<FastqWriter> secondOfPair, final FastqWriter unpaired, List<FastqWriter> tagWriters) {
            this.firstOfPair = firstOfPair;
            this.unpaired = unpaired;
            this.secondOfPair = secondOfPair;
            this.tagWriters = tagWriters == null ? Collections.emptyList(): tagWriters;
        }

        /** Simple constructor; all writers are pre-initialized.. */
        private FastqWriters(final FastqWriter firstOfPair, final FastqWriter secondOfPair, final FastqWriter unpaired, List<FastqWriter> tagWriters) {
            this(firstOfPair, new Lazy<>(() -> secondOfPair), unpaired, tagWriters);
        }

        /** Constructor if the consumer wishes for the second-of-pair writer to be built on-the-fly. */
        private FastqWriters(final FastqWriter firstOfPair, final Lazy<FastqWriter> secondOfPair, final FastqWriter unpaired) {
            this(firstOfPair, secondOfPair, unpaired, null);
        }

        /** Simple constructor; all writers are pre-initialized.. */
        private FastqWriters(final FastqWriter firstOfPair, final FastqWriter secondOfPair, final FastqWriter unpaired) {
            this(firstOfPair, new Lazy<>(() -> secondOfPair), unpaired, null);
        }

        public FastqWriter getFirstOfPair() {
            return firstOfPair;
        }

        public FastqWriter getSecondOfPair() {
            return secondOfPair.get();
        }

        public FastqWriter getUnpaired() {
            return unpaired;
        }

        public void closeAll() {
            final Set<FastqWriter> fastqWriters = new HashSet<FastqWriter>();
            fastqWriters.add(firstOfPair);
            fastqWriters.add(unpaired);
            fastqWriters.addAll(tagWriters);
            // Make sure this is a no-op if the second writer was never fetched.
            if (secondOfPair.isInitialized()) fastqWriters.add(secondOfPair.get());
            for (final FastqWriter fastqWriter : fastqWriters) {
                fastqWriter.close();
            }
        }
    }
}
