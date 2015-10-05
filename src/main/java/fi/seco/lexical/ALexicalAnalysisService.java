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
	public String baseform(String string, Locale lang, boolean segments) {
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
			boolean segments, boolean baseform, Locale lang) {
		throw new UnsupportedOperationException();	}

	@Override
	public Collection<Locale> getSupportedInflectionLocales() {
		return Collections.emptyList();
	}

}
