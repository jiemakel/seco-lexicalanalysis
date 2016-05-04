package fi.seco.lexical;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ALexicalAnalysisService implements ILexicalAnalysisService {

	@Override
	public String summarize(String string, Locale lang) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<Locale> getSupportedSummarizeLocales() {
		return Collections.emptyList();
	}

	@Override
	public String baseform(String string, Locale lang, boolean markSegments, boolean guessUnknown, int maxErrorCorrectDistance) {
		throw new UnsupportedOperationException();	}

	@Override
	public Collection<Locale> getSupportedBaseformLocales() {
		return Collections.emptyList();
	}

	@Override
	public String hyphenate(String string, Locale lang) {
		throw new UnsupportedOperationException();	}

	@Override
	public Collection<Locale> getSupportedHyphenationLocales() {
		return Collections.emptyList();
	}

	@Override
	public String inflect(String string, List<String> inflections,
			boolean markSegments, boolean baseform, boolean guessUnknown, int maxErrorCorrectDistance, Locale lang) {
		throw new UnsupportedOperationException();	}

	@Override
	public Collection<Locale> getSupportedInflectionLocales() {
		return Collections.emptyList();
	}

	@Override
	public Collection<String> split(String string, Locale lang) {
		return LexicalAnalysisUtil.split(string);
	}

	@Override
	public Collection<Locale> getSupportedSplitLocales() {
		return Collections.emptyList();
	}
	
	@Override
	public Collection<String> tokenize(String string, Locale lang) {
		return LexicalAnalysisUtil.tokenize(string);	
	}

	@Override
	public Collection<Locale> getSupportedTokenizationLocales() {
		return Collections.emptyList();
	}
}
