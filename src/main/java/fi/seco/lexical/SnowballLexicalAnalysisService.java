package fi.seco.lexical;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.tartarus.snowball.SnowballProgram;
import org.tartarus.snowball.ext.DanishStemmer;
import org.tartarus.snowball.ext.DutchStemmer;
import org.tartarus.snowball.ext.EnglishStemmer;
import org.tartarus.snowball.ext.FinnishStemmer;
import org.tartarus.snowball.ext.FrenchStemmer;
import org.tartarus.snowball.ext.GermanStemmer;
import org.tartarus.snowball.ext.ItalianStemmer;
import org.tartarus.snowball.ext.NorwegianStemmer;
import org.tartarus.snowball.ext.PortugueseStemmer;
import org.tartarus.snowball.ext.RussianStemmer;
import org.tartarus.snowball.ext.SpanishStemmer;
import org.tartarus.snowball.ext.SwedishStemmer;

public class SnowballLexicalAnalysisService extends ALexicalAnalysisService {

	private final Map<Locale, SnowballProgram> s = new HashMap<Locale, SnowballProgram>();
	{
		s.put(new Locale("dk"), new DanishStemmer());
		s.put(new Locale("nl"), new DutchStemmer());
		s.put(new Locale("en"), new EnglishStemmer());
		s.put(new Locale("fi"), new FinnishStemmer());
		s.put(new Locale("fr"), new FrenchStemmer());
		s.put(new Locale("fr"), new FrenchStemmer());
		s.put(new Locale("de"), new GermanStemmer());
		s.put(new Locale("it"), new ItalianStemmer());
		s.put(new Locale("no"), new NorwegianStemmer());
		s.put(new Locale("pt"), new PortugueseStemmer());
		s.put(new Locale("ru"), new RussianStemmer());
		s.put(new Locale("es"), new SpanishStemmer());
		s.put(new Locale("sv"), new SwedishStemmer());
	}

	private final static Pattern sp = Pattern.compile("\\P{IsL}+");

	@Override
	public String baseform(String string, Locale lang, boolean partition) {
		if (lang == null) return string;
		lang = new Locale(lang.getLanguage());
		if (s.containsKey(lang)) {
			SnowballProgram sbp = s.get(lang);
			StringBuilder sb = new StringBuilder();
			synchronized (sbp) {
				String[] labels = sp.split(string);
				for (int i = 0; i < labels.length; i++) {
					sbp.setCurrent(labels[i]);
					sbp.stem();
					sb.append(sbp.getCurrent());
					sb.append(' ');
				}
				return sb.toString().trim();
			}
		}
		return string;
	}

	@Override
	public Collection<Locale> getSupportedBaseformLocales() {
		return s.keySet();
	}

}
