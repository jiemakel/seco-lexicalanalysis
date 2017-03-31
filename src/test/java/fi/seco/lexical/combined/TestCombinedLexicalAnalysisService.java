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
		assertBestMatchIs(word.get(), "Venäjä");
	}
	
	private void assertBestMatchIs(WordToResults wtr, String lemma) {
		wtr.getAnalysis().stream()
		.filter(a -> a.getGlobalTags().containsKey("BEST_MATCH"))
		.forEach(m -> {
			String alemma = toLemma(m.getParts());
			if (!lemma.equals(alemma)) fail(lemma + " is not best match. Instead got "+alemma);
		});
		
	}

	@Test
	public void testHelsinki() {
		// Helsing vs Helsinki
		List<WordToResults> results = las.analyze("Helsingin", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(results.size(), 1);
		assertBestMatchIs(results.get(0), "Helsinki");
	}

	@Test
	public void testMannerheimCross() {
		// Mannerheim-risti could at one point generate 'Mannerheim-[WORD_ID=risti' and ' Mannerheim[WORD_ID=risti'
		List<WordToResults> results = las.analyze("Mannerheim-risti", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertEquals(results.size(), 1);
		assertTrue(results.toString(), results.get(0).getAnalysis().stream().allMatch(a -> !toLemma(a.getParts()).contains("[WORD_ID")));
	}

}
