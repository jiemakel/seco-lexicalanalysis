package fi.seco.lexical.combined;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import org.junit.Test;
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
	
	private void assertBestMatchIs(String lemma, WordToResults wtr) {
		wtr.getAnalysis().stream()
		.filter(a -> a.getGlobalTags().containsKey("BEST_MATCH"))
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

	
}
