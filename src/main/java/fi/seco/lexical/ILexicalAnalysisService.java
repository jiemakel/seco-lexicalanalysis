package fi.seco.lexical;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public interface ILexicalAnalysisService {
	public String summarize(String string, Locale lang);

	public Collection<Locale> getSupportedSummarizeLocales();

	public String baseform(String string, Locale lang, boolean markSegments, boolean guessUnknown, int maxErrorCorrectDistance);

	public Collection<Locale> getSupportedBaseformLocales();

	public String hyphenate(String string, Locale lang);

	public Collection<Locale> getSupportedHyphenationLocales();

	public String inflect(String string, List<String> inflections, boolean markSegments, boolean baseform, boolean guessUnknown, int maxErrorCorrectDistance, Locale lang);

	public Collection<Locale> getSupportedInflectionLocales();
	
	public Collection<Locale> getSupportedSplitLocales();
	
	public Collection<String> split(String text, Locale lang);
	
	public Collection<Locale> getSupportedTokenizationLocales();
	
	public Collection<String> tokenize(String text, Locale lang);
	
}
