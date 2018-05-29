package fi.seco.lexical.combined;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import org.junit.Test;

import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.WordToResults;

public class TestCombinedLexicalAnalysisService {
	
	final CombinedLexicalAnalysisService las = new CombinedLexicalAnalysisService();
	
	private String toLemma(List<WordPart> wps) {
		return wps.stream().map(wp -> wp.getLemma()).collect(Collectors.joining(""));
	}
	
	@Test
	public void testRussia() {
		// Venäjä vs venäjä
		List<WordToResults> results = las.analyze("Suomalaismies Venäjän", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		Optional<WordToResults> word = results.stream().filter(r -> r.getWord().equals("Venäjän")).findFirst(); 
		assertTrue("Couldn't find Venäjän in analysis",word.isPresent());
		assertBestMatchIs("Venäjä", word.get());
	}
	
	private Stream<Result> getBestMatches(WordToResults wtr) {
		return wtr.getAnalysis().stream()
		.filter(a -> a.getGlobalTags().containsKey("BEST_MATCH"));
	}

	private void assertBestMatchIs(String lemma, WordToResults wtr) {
		getBestMatches(wtr)
			.forEach(m -> {
				String alemma = toLemma(m.getParts());
				if (!lemma.equals(alemma)) fail(lemma + " is not best match. Instead got "+alemma+ " (from "+wtr.toString()+")");
			});
	}

	@Test
	public void testHelsinki() {
		// Helsing vs Helsinki
		List<WordToResults> results = las.analyze("Helsingin", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(1, results.size());
		assertBestMatchIs("Helsinki", results.get(0));
	}
	
	@Test
	public void testParis() {
		// Pariisi vs pari
		List<WordToResults> results = las.analyze("Pariisi", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(1, results.size());
		assertBestMatchIs("pari", results.get(0));
		results = las.analyze("Pariisi on", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(3, results.size());
		assertBestMatchIs("Pariisi", results.get(0));
	}

	@Test
	public void testMannerheimCross() {
		// Mannerheim-risti could at one point generate 'Mannerheim-[WORD_ID=risti' and ' Mannerheim[WORD_ID=risti'
		List<WordToResults> results = las.analyze("Mannerheim-risti", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(results.size(), 1);
		assertTrue(results.toString(), results.get(0).getAnalysis().stream().allMatch(a -> !toLemma(a.getParts()).contains("[WORD_ID")));
	}

	@Test
	public void testMannerheimAtEndOfSentence() {
		// The machine learned tokenizer used to tokenize everything ending with m. together, e.g. "Mannerheim." 
		List<WordToResults> results = las.analyze("Mannerheim.", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(2, results.size());
		assertBestMatchIs("Mannerheim", results.get(0));
		assertBestMatchIs(".", results.get(1));
	}

	@Test
	public void testThatMultiSentenceProcessingDoesNotRepeatTags() {
		List<WordToResults> results = las.analyze("Minä olen Mannerheim. Mannerheim on mies. Mannerheim kävi Helsingissä.", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertFalse("Multiple BEST_MATCHES in "+results, results.stream().anyMatch(p -> p.getAnalysis().stream().anyMatch(a -> a.getGlobalTags().containsKey("BEST_MATCH") && a.getGlobalTags().get("BEST_MATCH").size() > 1)));
		assertFalse("Multiple POS_MATCHES in "+results, results.stream().anyMatch(p -> p.getAnalysis().stream().anyMatch(a -> a.getGlobalTags().containsKey("POS_MATCH") && a.getGlobalTags().get("POS_MATCH").size() > 1)));
	}
	
	@Test
	public void testBaseforming() {
		assertEquals("suvakki ja Soldiers of Odin",las.baseform("suvakeilla ja Soldiers of Odineille",new Locale("fi"), false, true, 0));
		assertEquals("mobil apparat",las.baseform("mobila apparater",new Locale("sv"), false, true, 0));
	}

	@Test
	public void testThatOCRCorrectionAndGuessingBothGetABestMatch() {
		List<WordToResults> results = las.analyze("maalieja", new Locale("fi"),Collections.EMPTY_LIST,false,true,false,1,1);
		assertEquals(2, results.get(0).getAnalysis().stream().filter(r -> r.getGlobalTags().containsKey("BEST_MATCH")).count());
	}
	
	@Test
	public void testAnalysisOrdering() {
		List<WordToResults> res = las.analyze("kuin", new Locale("fi"), Collections.EMPTY_LIST, false, true, true, 1);
		WordToResults wtr = res.get(0);
		assertTrue(wtr.getAnalysis().get(0).getGlobalTags().containsKey("BEST_MATCH"));
		assertEquals("kuin",wtr.getAnalysis().get(0).getParts().get(0).getLemma());
		assertEquals("kuu",wtr.getAnalysis().get(wtr.getAnalysis().size()-2).getParts().get(0).getLemma());
		assertEquals("kuti",wtr.getAnalysis().get(wtr.getAnalysis().size()-1).getParts().get(0).getLemma());
	}

	@Test
	public void testBaseformOrdering() {
		List<List<String>> res = las.baseform("kuin suvakeilla", new Locale("fi"), false, true, 0, false);
		List<String> wtr = res.get(0);
		assertEquals(3, wtr.size());
		assertEquals("kuin",wtr.get(0));
		assertEquals("kuin",wtr.get(1));
		assertEquals("kuin",wtr.get(2));
		wtr = res.get(1);
		assertEquals(" ",wtr.get(0));
		wtr = res.get(2);
		assertEquals("suvakki",wtr.get(0));
		res = las.baseform("kuin suvakeilla", new Locale("fi"), false, true, 0, true);
		wtr = res.get(0);
		assertEquals("kuin",wtr.get(0));
		assertEquals("kuu",wtr.get(wtr.size()-2));
		assertEquals("kuti",wtr.get(wtr.size()-1));
		wtr = res.get(1);
		assertEquals(" ",wtr.get(0));
		wtr = res.get(2);
		assertEquals("suvakki",wtr.get(0));
	}
	
	@Test
	public void testDependencyParsing() {
		List<WordToResults> results = las.analyze("karkkini ostin torilta", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(5, results.size());
		assertBestMatchIs("karkki", results.get(0));
		getBestMatches(results.get(0))
			.forEach(m -> {
				assertEquals("3", m.getGlobalTags().get("HEAD").get(0));
				assertEquals("dobj", m.getGlobalTags().get("DEPREL").get(0));
			});
		assertBestMatchIs("ostaa", results.get(2));
		getBestMatches(results.get(2))
			.forEach(m -> {
				assertEquals("0", m.getGlobalTags().get("HEAD").get(0));
				assertEquals("ROOT", m.getGlobalTags().get("DEPREL").get(0));
			});
		assertBestMatchIs("tori", results.get(4));
		getBestMatches(results.get(4))
			.forEach(m -> {
				assertEquals("3", m.getGlobalTags().get("HEAD").get(0));
				assertEquals("nommod", m.getGlobalTags().get("DEPREL").get(0));
			});
	}
	
}
