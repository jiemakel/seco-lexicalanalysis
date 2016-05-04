package fi.seco.lexical;

import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LexicalAnalysisUtil {

	private final static Pattern sp = Pattern.compile("\\p{P}*(^|\\p{Z}+|$)\\p{P}*");
	private final static Pattern dp = Pattern.compile("([^\\p{C}\\p{P}\\p{Z}\\p{S}]+)");
	
	private final static Pattern sentenceSplit = Pattern.compile("(?<=[.?!;])\\s+(?=\\p{Lu})");

	private final static Pattern numbers = Pattern.compile("\\p{N}+");

	public static Collection<String> tokenize(String str) {
		return Arrays.asList(sp.split(str));
	}
	
	public static Collection<String> split(String str) {
		return Arrays.asList(sentenceSplit.split(str));
	}

	public static String normalize(String str) {
		return sp.matcher(str).replaceAll(" ").trim();
	}

	public static Matcher spaceMatcher(String str) {
		return sp.matcher(str);
	}

	public static Matcher dataMatcher(String str) {
		return dp.matcher(str);
	}

	public static boolean isNumber(String str) {
		return numbers.matcher(str).matches();
	}
}
