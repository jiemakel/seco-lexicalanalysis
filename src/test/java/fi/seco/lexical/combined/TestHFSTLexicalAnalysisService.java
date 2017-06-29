package fi.seco.lexical.combined;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

	
}
