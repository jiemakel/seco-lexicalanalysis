package fi.seco.lexical;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public interface ILexicalAnalysisService {
	public String summarize(String string, Locale lang);

	public Collection<Locale> getSupportedSummarizeLocales();

	public String baseform(String string, Locale lang, boolean segments, boolean guessUnknown);

	public Collection<Locale> getSupportedBaseformLocales();

	public String hyphenate(String string, Locale lang);

	public Collection<Locale> getSupportedHyphenationLocales();

	public String inflect(String string, List<String> inflections, boolean segments, boolean baseform, boolean guessUnknown, Locale lang);

	public Collection<Locale> getSupportedInflectionLocales();
	
}
