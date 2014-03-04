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

package org.broadinstitute.gatk.utils.activeregion;


// the imports for unit testing.


import htsjdk.samtools.reference.ReferenceSequenceFile;
import org.apache.commons.lang.ArrayUtils;
import htsjdk.tribble.readers.LineIterator;
import org.broadinstitute.gatk.utils.BaseTest;
import org.broadinstitute.gatk.utils.GenomeLoc;
import org.broadinstitute.gatk.utils.GenomeLocParser;
import org.broadinstitute.gatk.utils.MathUtils;
import org.broadinstitute.gatk.utils.collections.Pair;
import org.broadinstitute.gatk.utils.fasta.CachingIndexedFastaSequenceFile;
import org.broadinstitute.gatk.utils.variant.GATKVCFUtils;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


public class BandPassActivityProfileUnitTest extends BaseTest {
    private final static boolean DEBUG = false;
    private GenomeLocParser genomeLocParser;

    @BeforeClass
    public void init() throws FileNotFoundException {
        // sequence
        ReferenceSequenceFile seq = new CachingIndexedFastaSequenceFile(new File(b37KGReference));
        genomeLocParser = new GenomeLocParser(seq);
    }

    @DataProvider(name = "BandPassBasicTest")
    public Object[][] makeBandPassTest() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        for ( int start : Arrays.asList(1, 10, 100, 1000) ) {
            for ( boolean precedingIsActive : Arrays.asList(true, false) ) {
                for ( int precedingSites: Arrays.asList(0, 1, 10, 100) ) {
                    for ( int bandPassSize : Arrays.asList(0, 1, 10, 100) ) {
                        for ( double sigma : Arrays.asList(1.0, 2.0, BandPassActivityProfile.DEFAULT_SIGMA) ) {
//        for ( int start : Arrays.asList(10) ) {
//            for ( boolean precedingIsActive : Arrays.asList(false) ) {
//                for ( int precedingSites: Arrays.asList(0) ) {
//                    for ( int bandPassSize : Arrays.asList(1) ) {
                            tests.add(new Object[]{ start, precedingIsActive, precedingSites, bandPassSize, sigma });
                        }
                    }
                }
            }
        }

        return tests.toArray(new Object[][]{});
    }

    @Test(enabled = ! DEBUG, dataProvider = "BandPassBasicTest")
    public void testBandPass(final int start, final boolean precedingIsActive, final int nPrecedingSites, final int bandPassSize, final double sigma) {
        final BandPassActivityProfile profile = new BandPassActivityProfile(genomeLocParser, null, bandPassSize, sigma, false);

        final int expectedBandSize = bandPassSize * 2 + 1;
        Assert.assertEquals(profile.getFilteredSize(), bandPassSize, "Wrong filter size");
        Assert.assertEquals(profile.getSigma(), sigma, "Wrong sigma");
        Assert.assertEquals(profile.getBandSize(), expectedBandSize, "Wrong expected band size");

        final String contig = genomeLocParser.getContigs().getSequences().get(0).getSequenceName();
        final double precedingProb = precedingIsActive ? 1.0 : 0.0;
        for ( int i = 0; i < nPrecedingSites; i++ ) {
            final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, i + start);
            final ActivityProfileState state = new ActivityProfileState(loc, precedingProb);
            profile.add(state);
        }

        final GenomeLoc nextLoc = genomeLocParser.createGenomeLoc(contig, nPrecedingSites + start);
        profile.add(new ActivityProfileState(nextLoc, 1.0));

        if ( precedingIsActive == false && nPrecedingSites >= bandPassSize && bandPassSize < start ) {
            // we have enough space that all probs fall on the genome
            final double[] probs = profile.getProbabilitiesAsArray();
            Assert.assertEquals(MathUtils.sum(probs), 1.0 * (nPrecedingSites * precedingProb + 1), 1e-3, "Activity profile doesn't sum to number of non-zero prob states");
        }
    }

    private double[] bandPassInOnePass(final BandPassActivityProfile profile, final double[] activeProbArray) {
        final double[] bandPassProbArray = new double[activeProbArray.length];

        // apply the band pass filter for activeProbArray into filteredProbArray
        final double[] GaussianKernel = profile.getKernel();
        for( int iii = 0; iii < activeProbArray.length; iii++ ) {
            final double[] kernel = ArrayUtils.subarray(GaussianKernel, Math.max(profile.getFilteredSize() - iii, 0), Math.min(GaussianKernel.length, profile.getFilteredSize() + activeProbArray.length - iii));
            final double[] activeProbSubArray = ArrayUtils.subarray(activeProbArray, Math.max(0,iii - profile.getFilteredSize()), Math.min(activeProbArray.length,iii + profile.getFilteredSize() + 1));
            bandPassProbArray[iii] = dotProduct(activeProbSubArray, kernel);
        }

        return bandPassProbArray;
    }

    public static double dotProduct(double[] v1, double[] v2) {
        Assert.assertEquals(v1.length,v2.length,"Array lengths do not mach in dotProduct");
        double result = 0.0;
        for (int k = 0; k < v1.length; k++)
            result += v1[k] * v2[k];

        return result;
    }

    @DataProvider(name = "BandPassComposition")
    public Object[][] makeBandPassComposition() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        for ( int bandPassSize : Arrays.asList(0, 1, 10, 100, BandPassActivityProfile.MAX_FILTER_SIZE) ) {
            for ( int integrationLength : Arrays.asList(1, 10, 100, 1000) ) {
                tests.add(new Object[]{ bandPassSize, integrationLength });
            }
        }

        return tests.toArray(new Object[][]{});
    }

    @Test( enabled = ! DEBUG, dataProvider = "BandPassComposition")
    public void testBandPassComposition(final int bandPassSize, final int integrationLength) {
        final int start = 1;
        final BandPassActivityProfile profile = new BandPassActivityProfile(genomeLocParser, null, bandPassSize, BandPassActivityProfile.DEFAULT_SIGMA);
        final double[] rawActiveProbs = new double[integrationLength + bandPassSize * 2];

        // add a buffer so that we can get all of the band pass values
        final String contig = genomeLocParser.getContigs().getSequences().get(0).getSequenceName();
        int pos = start;
        int rawProbsOffset = 0;
        for ( int i = 0; i < bandPassSize; i++ ) {
            final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, pos++);
            final ActivityProfileState state = new ActivityProfileState(loc, 0.0);
            profile.add(state);
            rawActiveProbs[rawProbsOffset++] = 0.0;
            rawActiveProbs[rawActiveProbs.length - rawProbsOffset] = 0.0;
        }

        for ( int i = 0; i < integrationLength; i++ ) {
            final GenomeLoc nextLoc = genomeLocParser.createGenomeLoc(contig, pos++);
            profile.add(new ActivityProfileState(nextLoc, 1.0));
            rawActiveProbs[rawProbsOffset++] = 1.0;

            for ( int j = 0; j < profile.size(); j++ ) {
                Assert.assertTrue(profile.getStateList().get(j).isActiveProb >= 0.0, "State probability < 0 at " + j);
                Assert.assertTrue(profile.getStateList().get(j).isActiveProb <= 1.0 + 1e-3, "State probability > 1 at " + j);
            }
        }

        final double[] expectedProbs = bandPassInOnePass(profile, rawActiveProbs);
        for ( int j = 0; j < profile.size(); j++ ) {
            Assert.assertEquals(profile.getStateList().get(j).isActiveProb, expectedProbs[j], "State probability not expected at " + j);
        }
    }

    // ------------------------------------------------------------------------------------
    //
    // Code to test the creation of the kernels
    //
    // ------------------------------------------------------------------------------------

    /**

     kernel <- function(sd, pThres) {
     raw = dnorm(-80:81, mean=0, sd=sd)
     norm = raw / sum(raw)
     bad = norm < pThres
     paste(norm[! bad], collapse=", ")
     }

     print(kernel(0.01, 1e-5))
     print(kernel(1, 1e-5))
     print(kernel(5, 1e-5))
     print(kernel(17, 1e-5))

     * @return
     */

    @DataProvider(name = "KernelCreation")
    public Object[][] makeKernelCreation() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        tests.add(new Object[]{ 0.01, 1000, new double[]{1.0}});
        tests.add(new Object[]{ 1.0, 1000, new double[]{0.0001338302, 0.004431848, 0.053990966, 0.241970723, 0.398942278, 0.241970723, 0.053990966, 0.004431848, 0.0001338302}});
        tests.add(new Object[]{ 1.0, 0, new double[]{1.0}});
        tests.add(new Object[]{ 1.0, 1, new double[]{0.2740686, 0.4518628, 0.2740686}});
        tests.add(new Object[]{ 1.0, 2, new double[]{0.05448868, 0.24420134, 0.40261995, 0.24420134, 0.05448868}});
        tests.add(new Object[]{ 1.0, 1000, new double[]{0.0001338302, 0.004431848, 0.053990966, 0.241970723, 0.398942278, 0.241970723, 0.053990966, 0.004431848, 0.0001338302}});
        tests.add(new Object[]{ 5.0, 1000, new double[]{1.1788613551308e-05, 2.67660451529771e-05, 5.83893851582921e-05, 0.000122380386022754, 0.000246443833694604, 0.000476817640292968, 0.000886369682387602, 0.00158309031659599, 0.00271659384673712, 0.00447890605896858, 0.00709491856924629, 0.0107981933026376, 0.0157900316601788, 0.0221841669358911, 0.029945493127149, 0.0388372109966426, 0.0483941449038287, 0.0579383105522965, 0.0666449205783599, 0.0736540280606647, 0.0782085387950912, 0.0797884560802865, 0.0782085387950912, 0.0736540280606647, 0.0666449205783599, 0.0579383105522965, 0.0483941449038287, 0.0388372109966426, 0.029945493127149, 0.0221841669358911, 0.0157900316601788, 0.0107981933026376, 0.00709491856924629, 0.00447890605896858, 0.00271659384673712, 0.00158309031659599, 0.000886369682387602, 0.000476817640292968, 0.000246443833694604, 0.000122380386022754, 5.83893851582921e-05, 2.67660451529771e-05, 1.1788613551308e-05}});
        tests.add(new Object[]{17.0, 1000, new double[]{1.25162575710745e-05, 1.57001772728555e-05, 1.96260034693739e-05, 2.44487374842009e-05, 3.03513668801384e-05, 3.75489089511911e-05, 4.62928204154855e-05, 5.68757597480354e-05, 6.96366758708924e-05, 8.49661819944029e-05, 0.000103312156275406, 0.000125185491708561, 0.000151165896477646, 0.000181907623161359, 0.000218144981137171, 0.000260697461819069, 0.000310474281706066, 0.000368478124457557, 0.000435807841336874, 0.00051365985048857, 0.000603327960854364, 0.000706201337376934, 0.000823760321812988, 0.000957569829285965, 0.00110927005589186, 0.00128056425833231, 0.00147320340358764, 0.00168896753568649, 0.00192964376796036, 0.00219700088266432, 0.00249276060490197, 0.00281856571330067, 0.00317594525418154, 0.00356627723683793, 0.00399074930220799, 0.00445031797242299, 0.00494566720070898, 0.00547716704583487, 0.00604483338842317, 0.00664828968356621, 0.00728673180099395, 0.00795889703644795, 0.00866303838230695, 0.00939690511889675, 0.0101577307281371, 0.010942229037054, 0.0117465993701676, 0.0125665413280325, 0.0133972796167302, 0.0142335991336574, 0.0150698902735454, 0.0159002041614507, 0.0167183172536454, 0.0175178044808441, 0.0182921198494897, 0.0190346831745763, 0.0197389714002676, 0.020398612780527, 0.0210074820484496, 0.0215597946062309, 0.0220501977225941, 0.022473856734247, 0.0228265343139947, 0.0231046609899767, 0.0233053952756892, 0.0234266719946158, 0.0234672376502799, 0.0234266719946158, 0.0233053952756892, 0.0231046609899767, 0.0228265343139947, 0.022473856734247, 0.0220501977225941, 0.0215597946062309, 0.0210074820484496, 0.020398612780527, 0.0197389714002676, 0.0190346831745763, 0.0182921198494897, 0.0175178044808441, 0.0167183172536454, 0.0159002041614507, 0.0150698902735454, 0.0142335991336574, 0.0133972796167302, 0.0125665413280325, 0.0117465993701676, 0.010942229037054, 0.0101577307281371, 0.00939690511889675, 0.00866303838230695, 0.00795889703644795, 0.00728673180099395, 0.00664828968356621, 0.00604483338842317, 0.00547716704583487, 0.00494566720070898, 0.00445031797242299, 0.00399074930220799, 0.00356627723683793, 0.00317594525418154, 0.00281856571330067, 0.00249276060490197, 0.00219700088266432, 0.00192964376796036, 0.00168896753568649, 0.00147320340358764, 0.00128056425833231, 0.00110927005589186, 0.000957569829285965, 0.000823760321812988, 0.000706201337376934, 0.000603327960854364, 0.00051365985048857, 0.000435807841336874, 0.000368478124457557, 0.000310474281706066, 0.000260697461819069, 0.000218144981137171, 0.000181907623161359, 0.000151165896477646, 0.000125185491708561, 0.000103312156275406, 8.49661819944029e-05, 6.96366758708924e-05, 5.68757597480354e-05, 4.62928204154855e-05, 3.75489089511911e-05, 3.03513668801384e-05, 2.44487374842009e-05, 1.96260034693739e-05, 1.57001772728555e-05, 1.25162575710745e-05}});

        return tests.toArray(new Object[][]{});
    }

    @Test( enabled = ! DEBUG, dataProvider = "KernelCreation")
    public void testKernelCreation(final double sigma, final int maxSize, final double[] expectedKernel) {
        final BandPassActivityProfile profile = new BandPassActivityProfile(genomeLocParser, null, maxSize, sigma, true);

        final double[] kernel = profile.getKernel();
        Assert.assertEquals(kernel.length, expectedKernel.length);
        for ( int i = 0; i < kernel.length; i++ )
            Assert.assertEquals(kernel[i], expectedKernel[i], 1e-3, "Kernels not equal at " + i);
    }

    // ------------------------------------------------------------------------------------
    //
    // Large-scale test, reading in 1000G Phase I chr20 calls and making sure that
    // the regions returned are the same if you run on the entire profile vs. doing it
    // incremental
    //
    // ------------------------------------------------------------------------------------

    @DataProvider(name = "VCFProfile")
    public Object[][] makeVCFProfile() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        //tests.add(new Object[]{ privateTestDir + "ALL.chr20.phase1_release_v3.20101123.snps_indels_svs.sites.vcf", "20", 60470, 61000});
        //tests.add(new Object[]{ privateTestDir + "ALL.chr20.phase1_release_v3.20101123.snps_indels_svs.sites.vcf", "20", 60470, 100000});
        //tests.add(new Object[]{ privateTestDir + "ALL.chr20.phase1_release_v3.20101123.snps_indels_svs.sites.vcf", "20", 60470, 1000000});
        tests.add(new Object[]{ privateTestDir + "ALL.chr20.phase1_release_v3.20101123.snps_indels_svs.sites.vcf", "20", 60470, 1000000});
        tests.add(new Object[]{ privateTestDir + "NA12878.WGS.b37.chr20.firstMB.vcf", "20", 1, 1000000});

        return tests.toArray(new Object[][]{});
    }

    @Test( dataProvider = "VCFProfile")
    public void testVCFProfile(final String path, final String contig, final int start, final int end) throws Exception {
        final int extension = 50;
        final int minRegionSize = 50;
        final int maxRegionSize = 300;

        final File file = new File(path);
        final VCFCodec codec = new VCFCodec();
        final Pair<VCFHeader, GATKVCFUtils.VCIterable<LineIterator>> reader = GATKVCFUtils.readAllVCs(file, codec);

        final List<ActiveRegion> incRegions = new ArrayList<ActiveRegion>();
        final BandPassActivityProfile incProfile = new BandPassActivityProfile(genomeLocParser, null);
        final BandPassActivityProfile fullProfile = new BandPassActivityProfile(genomeLocParser, null);
        int pos = start;
        for ( final VariantContext vc : reader.getSecond() ) {
            if ( vc == null ) continue;
            while ( pos < vc.getStart() ) {
                final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, pos);
                //logger.warn("Adding 0.0 at " + loc + " because vc.getStart is " + vc.getStart());
                incProfile.add(new ActivityProfileState(loc, 0.0));
                fullProfile.add(new ActivityProfileState(loc, 0.0));
                pos++;
            }
            if ( vc.getStart() >= start && vc.getEnd() <= end ) {
                final GenomeLoc loc = genomeLocParser.createGenomeLoc(contig, pos);
                //logger.warn("Adding 1.0 at " + loc);
                ActivityProfileState.Type type = ActivityProfileState.Type.NONE;
                Number value = null;
                if ( vc.isBiallelic() && vc.isIndel() ) {
                    type = ActivityProfileState.Type.HIGH_QUALITY_SOFT_CLIPS;
                    value = Math.abs(vc.getIndelLengths().get(0));
                }
                final ActivityProfileState state = new ActivityProfileState(loc, 1.0, type, value);
                incProfile.add(state);
                fullProfile.add(state);
                pos++;
            }

            incRegions.addAll(incProfile.popReadyActiveRegions(extension, minRegionSize, maxRegionSize, false));

            if ( vc.getStart() > end )
                break;
        }

        incRegions.addAll(incProfile.popReadyActiveRegions(extension, minRegionSize, maxRegionSize, true));

        final List<ActiveRegion> fullRegions = fullProfile.popReadyActiveRegions(extension, minRegionSize, maxRegionSize, true);
        assertGoodRegions(fullRegions, start, end, maxRegionSize);
        assertGoodRegions(incRegions, start, end, maxRegionSize);

        Assert.assertEquals(incRegions.size(),  fullRegions.size(), "incremental and full region sizes aren't the same");
        for ( int i = 0; i < fullRegions.size(); i++ ) {
            final ActiveRegion incRegion = incRegions.get(i);
            final ActiveRegion fullRegion = fullRegions.get(i);
            Assert.assertTrue(incRegion.equalExceptReads(fullRegion), "Full and incremental regions are not equal: full = " + fullRegion + " inc = " + incRegion);
        }
    }

    private void assertGoodRegions(final List<ActiveRegion> regions, final int start, final int end, final int maxRegionSize) {
        int lastPosSeen = start - 1;
        for ( int regionI = 0; regionI < regions.size(); regionI++ ) {
            final ActiveRegion region = regions.get(regionI);
            Assert.assertEquals(region.getLocation().getStart(), lastPosSeen + 1, "discontinuous with previous region.  lastPosSeen " + lastPosSeen + " but region is " + region);
            Assert.assertTrue(region.getLocation().size() <= maxRegionSize, "Region is too big: " + region);
            lastPosSeen = region.getLocation().getStop();

            for ( final ActivityProfileState state : region.getSupportingStates() ) {
                Assert.assertEquals(state.isActiveProb > ActivityProfile.ACTIVE_PROB_THRESHOLD, region.isActive(),
                        "Region is active=" + region.isActive() + " but contains a state " + state + " with prob "
                                + state.isActiveProb + " not within expected values given threshold for activity of "
                                + ActivityProfile.ACTIVE_PROB_THRESHOLD);
            }
        }
    }
}
