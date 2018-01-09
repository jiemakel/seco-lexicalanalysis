package fi.seco.lexical.combined;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import org.junit.Test;

import fi.seco.lexical.hfst.HFSTLexicalAnalysisService;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.WordToResults;

public class TestHFSTLexicalAnalysisService {
	
	final HFSTLexicalAnalysisService las = new HFSTLexicalAnalysisService();
	
	private String toLemma(List<WordPart> wps) {
		return wps.stream().map(wp -> wp.getLemma()).collect(Collectors.joining(""));
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
		List<List<String>> res = las.baseform("kuin", new Locale("fi"), false, true, 1, true);
		List<String> wtr = res.get(0);
		assertEquals("kuin",wtr.get(0));
		assertEquals("kuu",wtr.get(wtr.size()-2));
		assertEquals("kuti",wtr.get(wtr.size()-1));
	}
	
	@Test
	public void testThatMultiSentenceProcessingDoesNotRepeatTags() {
		List<WordToResults> results = las.analyze("Minä olen Mannerheim. Mannerheim on mies. Mannerheim kävi Helsingissä.", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2);
		assertFalse("Multiple BEST_MATCHES in "+results, results.stream().anyMatch(p -> p.getAnalysis().stream().anyMatch(a -> a.getGlobalTags().containsKey("BEST_MATCH") && a.getGlobalTags().get("BEST_MATCH").size() > 1)));
		assertFalse("Multiple POS_MATCHES in "+results, results.stream().anyMatch(p -> p.getAnalysis().stream().anyMatch(a -> a.getGlobalTags().containsKey("POS_MATCH") && a.getGlobalTags().get("POS_MATCH").size() > 1)));
	}
	
	@Test
	public void testBaseforming() {
		assertEquals("juosta, läpi yö",las.baseform("juoksin, läpi yön",new Locale("fi"), false, true, 0));
		assertEquals("mobil apparat",las.baseform("mobila apparater",new Locale("sv"), false, true, 0));
	}

	@Test
	public void testInflection() {
		assertTrue("Inflection support is not working! "+las.getSupportedInflectionLocales(),las.getSupportedInflectionLocales().contains(new Locale("fi")));
		assertEquals("Albert ostaminen fagotit ja töräyttäminen puhkuminen melodiat",las.inflect("Albert osti fagotin ja töräytti puhkuvan melodian", Arrays.asList(new String[] {"V N Nom Sg", "N Nom Pl", "A Pos Nom Pl"}),false , false, false, 0, new Locale("fi")));
	}
}
