package fi.seco.lexical.combined;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectLongHashMap;
import com.carrotsearch.hppc.ObjectLongMap;
import com.carrotsearch.hppc.procedures.ObjectIntProcedure;

import fi.seco.hfst.Transducer;
import fi.seco.lexical.LexicalAnalysisUtil;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;
import is2.data.Cluster;
import is2.data.Long2Int;
import is2.data.SentenceData09;
import is2.io.IOGenerals;
import is2.parser.Decoder;
import is2.parser.Edges;
import is2.parser.Extractor;
import is2.parser.ParametersFloat;
import is2.parser.Parser;
import is2.parser.Pipe;
import is2.util.OptionsSuper;
import marmot.core.Tagger;
import marmot.morph.Sentence;
import marmot.morph.Word;
import marmot.util.StringUtils;
import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

public class CombinedLexicalAnalysisService extends HFSTLexicalAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(CombinedLexicalAnalysisService.class);

	private final Map<Locale, SentenceModel> sdMap = new HashMap<Locale, SentenceModel>();
	private final Map<Locale, TokenizerModel> tMap = new HashMap<Locale, TokenizerModel>();
	private final Map<Locale, ObjectLongMap<String>> fMap = new HashMap<Locale, ObjectLongMap<String>>();

	private final Set<Locale> supportedLocales = new HashSet<Locale>();

	public CombinedLexicalAnalysisService() {
		try {
			BufferedReader r = new BufferedReader(new InputStreamReader(CombinedLexicalAnalysisService.class.getResourceAsStream("supported-locales")));
			String line;
			while ((line = r.readLine()) != null)
				supportedLocales.add(new Locale(line));
			r.close();
		} catch (IOException e) {
			log.error("Couldn't read locale information. Claiming to support no analysis/baseform languages");
		}
	}

	private SentenceDetector getSentenceDetector(Locale lang) {
		SentenceModel sd = sdMap.get(lang);
		if (sd != null) return new SentenceDetectorME(sd);
		InputStream modelIn = CombinedLexicalAnalysisService.class.getResourceAsStream(lang + "-sent.bin");
		try {
			sd = new SentenceModel(modelIn);
			sdMap.put(lang, sd);
			return new SentenceDetectorME(sd);
		} catch (IOException e) {
			throw new IOError(e);
		} finally {
			if (modelIn != null) try {
				modelIn.close();
			} catch (IOException e) {}
		}
	}
	
	private ObjectLongMap<String> getFrequencyMap(Locale lang) {
		ObjectLongMap<String> f = fMap.get(lang);
		if (f != null) return f;
		f = new ObjectLongHashMap<String>();		
		BufferedReader ff = new BufferedReader(new InputStreamReader(CombinedLexicalAnalysisService.class.getResourceAsStream(lang + "-lemma-frequencies.txt")));
		try {
		while (true) {
			
			String line = ff.readLine();
			if (line==null) break;
			String[] parts = line.split(" ");
			f.put(parts[1],Long.parseLong(parts[0]));
		}
		fMap.put(lang,f);
		} catch (IOException e) {
			throw new IOError(e);
		} finally {
			try {
				ff.close();
			} catch (IOException e) {}
		}
		return f;
	}

	private Tokenizer getTokenizer(Locale lang) {
		TokenizerModel t = tMap.get(lang);
		if (t != null) return new TokenizerME(t);
		InputStream modelIn = CombinedLexicalAnalysisService.class.getResourceAsStream(lang + "-token.bin");
		try {
			t = new TokenizerModel(modelIn);
			tMap.put(lang, t);
			return new TokenizerME(t);
		} catch (IOException e) {
			throw new IOError(e);
		} finally {
			if (modelIn != null) try {
				modelIn.close();
			} catch (IOException e) {}
		}
	}

	private static final Locale fi = new Locale("fi");

	private static final Tagger fitag;

	static {
		Tagger tmp = null;
		try {
			tmp = ((Tagger) new ObjectInputStream(new GZIPInputStream(CombinedLexicalAnalysisService.class.getResourceAsStream("fin_model.marmot"))).readObject());
		} catch (ClassNotFoundException e) {} catch (IOException e) {}
		fitag = tmp;
	}

	private static final Parser fiparser = new Parser();

	static {
		try {
			OptionsSuper options = new OptionsSuper();
			fiparser.options = options;
			Pipe pipe = new Pipe(options);
			fiparser.pipe = pipe;
			ParametersFloat params = new ParametersFloat(0);
			fiparser.params = params;
			ZipInputStream zis = new ZipInputStream(CombinedLexicalAnalysisService.class.getResourceAsStream("parser.model"));
			zis.getNextEntry();
			DataInputStream dis = new DataInputStream(zis);
			pipe.mf.read(dis);
			pipe.cl = new Cluster(dis);
			params.read(dis);
			Long2Int l2i = new Long2Int(params.size());
			fiparser.l2i = l2i;
			int THREADS = Runtime.getRuntime().availableProcessors();
			pipe.extractor = new Extractor[THREADS];

			boolean stack = dis.readBoolean();

			options.featureCreation = dis.readInt();

			for (int t = 0; t < THREADS; t++)
				pipe.extractor[t] = new Extractor(l2i, stack, options.featureCreation);

			Extractor.initFeatures();
			Extractor.initStat(options.featureCreation);

			for (int t = 0; t < THREADS; t++)
				pipe.extractor[t].init();

			Edges.read(dis);

			options.decodeProjective = dis.readBoolean();

			Extractor.maxForm = dis.readInt();

			boolean foundInfo = false;
			String info = null;
			int icnt = dis.readInt();
			for (int i = 0; i < icnt; i++)
				info = dis.readUTF();
			//                System.out.println(info);

			dis.close();

			Decoder.NON_PROJECTIVITY_THRESHOLD = (float) options.decodeTH;

			Extractor.initStat(options.featureCreation);
		} catch (IOException e) {
			throw new IOError(e);
		}

	}

	private static final Map<String, String> posMap = new HashMap<String, String>();

	private static final Map<String, Set<String>> rposMap = new HashMap<String, Set<String>>();

	static {
		posMap.put("NOUN", "POS_N");
		posMap.put("PROPN", "POS_N");
		posMap.put("VERB", "POS_V");
		posMap.put("ADV", "POS_Adv");
		posMap.put("ADP", "POS_Adp");
		posMap.put("PUNCT", "POS_Punct");
		posMap.put("PRON", "POS_Pron");
		posMap.put("ADJ", "POS_Adj");
		posMap.put("NUM", "POS_Num");
		posMap.put("INTJ", "POS_Interj");
		rposMap.put("N", new HashSet<String>(Arrays.asList(new String[] { "NOUN", "PROPN" })));
		rposMap.put("V", new HashSet<String>(Arrays.asList(new String[] { "VERB" })));
		rposMap.put("Adv", new HashSet<String>(Arrays.asList(new String[] { "ADV" })));
		rposMap.put("C", new HashSet<String>(Arrays.asList(new String[] { "CONJ" })));
		rposMap.put("Adp", new HashSet<String>(Arrays.asList(new String[] { "ADP" })));
		rposMap.put("A", new HashSet<String>(Arrays.asList(new String[] { "ADJ" })));
		rposMap.put("Punct", new HashSet<String>(Arrays.asList(new String[] { "PUNCT" })));
		rposMap.put("Pron", new HashSet<String>(Arrays.asList(new String[] { "PRON" })));
		rposMap.put("Num", new HashSet<String>(Arrays.asList(new String[] { "NUM" })));
	}

	private static final Map<String, String> conjMap = new HashMap<String, String>();

	static {
		conjMap.put("ADVERBIAL", "POS_Adv");
		conjMap.put("COORD", "POS_C");
	}

	private static String map(List<String> pos, String conj) {
		String ret=null;
		if (pos.contains("ADV")) {
			if (pos.contains("CONJ") && conj != null)
				ret = conjMap.get(conj);
			else
				for (String cpos : pos) if (ret==null && !cpos.equals("ADV"))  
					ret = posMap.get(cpos);
		} else for (String cpos : pos) if (ret==null)  
			ret = posMap.get(cpos);
		if (ret == null) //			System.err.println("Unknown POS: "+pos+","+subcat+","+conj);
			ret = "POS_" + pos.get(0).substring(0,1).toUpperCase() + pos.get(0).substring(1).toLowerCase();
		return ret;
	}

	private static Set<String> rmap(String pos) {
		Set<String> ret = rposMap.get(pos);
		if (ret != null) return ret;
		//		System.out.println("Unknown POS: "+pos);
		return Collections.singleton(pos);
	}

	@Override
	public String baseform(String string, Locale lang, boolean baseformSegments, boolean guessUnknown, int maxEditDistance) {
		return baseform(string, lang, baseformSegments, guessUnknown, maxEditDistance, 1);
	}

	public String baseform(String string, Locale lang, boolean baseformSegments, boolean guessUnknown, int maxEditDistance, int depth) {
		try {
			List<WordToResults> crc = analyze(string, lang, Collections.EMPTY_LIST, baseformSegments, guessUnknown, false, maxEditDistance, depth);
			StringBuilder ret = new StringBuilder();
			for (WordToResults cr : crc) {
				ret.append(getBestLemma(cr, lang, baseformSegments));
				ret.append(' ');
			}
			return ret.toString().trim();
		} catch (ArrayIndexOutOfBoundsException e) {
			return string;
		}
	}
	
	public List<WordToResults> analyze(String str, Locale lang, List<String> inflections, boolean baseformSegments, boolean guessUnknown, boolean segmentUnknown, int maxErrorCorrectDistance, int depth) {
		if (!supportedLocales.contains(lang)) return super.analyze(str, lang, inflections, baseformSegments, guessUnknown, segmentUnknown, maxErrorCorrectDistance);
		Tokenizer t = getTokenizer(lang);
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		int startOfSentenceInResults = 0;
		List<WordToResults> ret = new ArrayList<WordToResults>();
		int lastIndexInOriginal = 0;
		int curIndexInOriginal = 0;
		for (String sentence : getSentenceDetector(lang).sentDetect(str)) {
			lastIndexInOriginal = curIndexInOriginal;
			while (!str.substring(curIndexInOriginal).startsWith(sentence)) curIndexInOriginal++;
			if (lastIndexInOriginal != curIndexInOriginal) {
				String whitespace = str.substring(lastIndexInOriginal, curIndexInOriginal);
				ret.add(new WordToResults(whitespace, Collections.singletonList(new Result().addGlobalTag("WHITESPACE", "TRUE").addPart(new WordPart(whitespace)))));
			}
			int wordInSentence = 0;
			for (String word : t.tokenize(sentence)) {
				lastIndexInOriginal = curIndexInOriginal;
				while (!str.substring(curIndexInOriginal).startsWith(word)) curIndexInOriginal++;
				if (lastIndexInOriginal != curIndexInOriginal) {
					String whitespace = str.substring(lastIndexInOriginal, curIndexInOriginal);
					ret.add(new WordToResults(whitespace, Collections.singletonList(new Result().addGlobalTag("WHITESPACE", "TRUE").addPart(new WordPart(whitespace)))));
				}
				curIndexInOriginal += word.length();
				final List<Result> r = toResult(tc.analyze(word));
				if (wordInSentence++==0) for (Result res : r) res.addGlobalTag("FIRST_IN_SENTENCE", "TRUE");
				if (r.isEmpty() && maxErrorCorrectDistance>0) {
					Transducer tc2 = segmentUnknown ? getTransducer(lang,"analysis-fuzzy-segment",fuzzySegmentTransducers) : getTransducer(lang,"analysis-fuzzy",fuzzyTransducers);
					for (int j=1;j<=maxErrorCorrectDistance;j++) {
						for (String c : getEditDistance(lang, word,j)) {
							List<Transducer.Result> res2 = tc2.analyze(c);
							for (Transducer.Result r2: res2)
								if (r2.getWeight()<(j+1)*1000)
									r.add(toResult(r2).addGlobalTag("EDIT_DISTANCE", ""+j));
						}
						if (!r.isEmpty()) break;
					}
				}				
				if (r.isEmpty() && guessUnknown && word.length()>=4) { // Fixed cutoff, don't guess words shorter than 4 chars.
					Transducer tc2 = segmentUnknown ? getTransducer(lang,"analysis-guess-segment",guessSegmentTransducers) : getTransducer(lang,"analysis-guess",guessTransducers);
					String reversedLabel = StringUtils.reverse(word);
					List<Transducer.Result> analysis = Collections.EMPTY_LIST;
					int length = reversedLabel.length();
					while (analysis.isEmpty() && length>3) // Fixed cutoff of min 3 last chars to use 
						analysis = tc2.analyze(reversedLabel.substring(0,length--));
					if (!analysis.isEmpty()) {
						for (Transducer.Result tr: analysis) {
							if (tr.getSymbols().isEmpty()) continue;
							Collections.reverse(tr.getSymbols());
							if (!tr.getSymbols().get(0).startsWith("[")) tr.getSymbols().add(0,"[WORD_ID=");
						}
						ObjectIntHashMap<Result> gres = new ObjectIntHashMap<Result>();
						outer: for (Result gr : toResult(analysis)) {
							if (gr.getParts().isEmpty()) continue;
							boolean empty = true;
							for (WordPart p : gr.getParts()) if (!"".equals(p.getLemma())) {
								empty=false;
								break;
							}
							if (empty) continue;
							gr.getParts().get(0).getTags().remove("GUESS_CATEGORY");
							gr.getParts().get(0).getTags().remove("KAV");
							gr.getParts().get(0).getTags().remove("PROPER");
							gr.getParts().get(0).getTags().remove("SEM");
							List<String> pos = gr.getParts().get(0).getTags().get("UPOS");
							if (pos!=null) for (int j=0;j<pos.size();j++)
								if (pos.get(j).equals("PROPN")) pos.set(j,"NOUN");
							gr.getParts().get(0).setLemma(word.substring(0,word.length()-length-1)+gr.getParts().get(0).getLemma());
							List<String> gsegments = gr.getParts().get(0).getTags().get("SEGMENT");
							if (gsegments!=null) {
								List<String> nsegments = new ArrayList<String>();
								int clindex = word.length()-1;
								for (int j=gsegments.size()-1;j>=0;j--) {
									String cs = gsegments.get(j);
									if (cs.contains("{WB}")) continue outer;
									int cindex = cs.length()-1; 
									while (cindex>=0 && clindex>=0) {
										if (cs.charAt(cindex)=='»') cindex--;
										else {
											String tmp = cs.substring(0,cindex+1);
											if (tmp.endsWith("{WB}") || tmp.endsWith("{XB}") || tmp.endsWith("{DB}") || tmp.endsWith("{MB}")) cindex-=4;
											else if (tmp.endsWith("{STUB}")) cindex-=6;
											else if (tmp.endsWith("{hyph?}")) cindex-=7;
											else if (word.charAt(clindex--)!=cs.charAt(cindex--)) break;
										}
									}
									if (cindex!=-1) {
										nsegments.add(word.substring(0,clindex+2) + cs.substring(cindex+2));
										clindex=-1;
										break;
									} else nsegments.add(gsegments.get(j));
									if (j!=0 && clindex==-1) continue outer;
								}
								Collections.reverse(nsegments);
								if (clindex!=-1) nsegments.set(0,word.substring(0,clindex+1)+nsegments.get(0));
								gr.getParts().get(0).getTags().put("SEGMENT", nsegments);
							}
							gres.putOrAdd(gr, 1, 1);
						}
						gres.forEach(new ObjectIntProcedure<Result>() {
							public void apply(Result value, int v2) { value.setWeight(value.getWeight()/v2);value.addGlobalTag("GUESS_COUNT",""+v2); r.add(value); };
						});
					}
				}
				if (r.isEmpty())
					r.add(new Result().addGlobalTag("UNKNOWN", "TRUE").addPart(new WordPart(word)));
				if (baseformSegments)
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<WordToResults> analysis = super.analyze(wp.getLemma(), lang, Collections.EMPTY_LIST, false, guessUnknown, true,0);
							if (analysis.size()==0)
								continue;
							Result br = getBestResult(analysis.get(0));
							List<String> bwpSegments = new ArrayList<String>();
							for (WordPart bwp : br.getParts()) {
								if (bwp.getTags().containsKey("SEGMENT"))
									bwpSegments.addAll(bwp.getTags().get("SEGMENT"));
								else bwpSegments.add(bwp.getLemma());
								bwpSegments.add("{WB}");
							}
							bwpSegments.remove(bwpSegments.size()-1);
							wp.getTags().put("BASEFORM_SEGMENT",bwpSegments);
						}				
				if (!inflections.isEmpty() && supportedInflectionLocales.contains(lang)) {
					Transducer tic = getTransducer(lang, "inflection", inflectionTransducers);
					for (Result res : r)
						for (WordPart wp : res.getParts()) {
							List<String> inflectedC = new ArrayList<String>();
							List<String> inflectedFormC = new ArrayList<String>();
							for (String inflection : inflections) {
								String inflected = firstToString(tic.analyze(wp.getLemma() + " " + inflection));
								if (!inflected.isEmpty()) {
									inflectedC.add(inflected);
									inflectedFormC.add(inflection);
								}
							}
							if (!inflectedC.isEmpty()) {
								wp.getTags().put("INFLECTED", inflectedC);
								wp.getTags().put("INFLECTED_FORM", inflectedFormC);
							}
						}
				}
				ret.add(new WordToResults(word, r));
			}
			if (fi.equals(lang) && ret.size() - startOfSentenceInResults <= 120 && depth > 0) { // NOTE!: hard cutoff for sentence length
				List<Word> tokens = new ArrayList<Word>(ret.size() - startOfSentenceInResults);
				int j = startOfSentenceInResults;
				while (startOfSentenceInResults < ret.size()) {
					WordToResults wtr = ret.get(startOfSentenceInResults++);
					Set<String> tf = new HashSet<String>();
					for (Result r : wtr.getAnalysis()) {
						List<String> aPOS = r.getParts().isEmpty() ? null : r.getParts().get(r.getParts().size() - 1).getTags().get("UPOS");
						List<String> aCONJ = r.getParts().isEmpty() ? null : r.getParts().get(r.getParts().size() - 1).getTags().get("CONJ");
						if (aPOS == null)
							tf.add("_");
						else tf.add(map(aPOS, aCONJ == null ? null : aCONJ.get(0)));
					}
					String[] termFeatures = tf.toArray(new String[tf.size()]);
					Arrays.sort(termFeatures);
					tokens.add(new Word(wtr.getWord(), null, null, termFeatures, null, null));
				}
				List<List<String>> tags;
				synchronized (fitag) {
					tags = fitag.tag(new Sentence(tokens));
				}
				startOfSentenceInResults = j;
				for (int k = 0; k < tags.size(); k++) {
					WordToResults wtr = ret.get(startOfSentenceInResults++);
					for (Result r : wtr.getAnalysis()) {
						if (!r.getParts().isEmpty()) {
							if (r.getParts().size()==1 && (// Dirty hacks
								("kuin".equals(wtr.getWord().toLowerCase()) && "kuin".equals(r.getParts().get(0).getLemma())) ||
								("niiden".equals(wtr.getWord().toLowerCase()) && "ne".equals(r.getParts().get(0).getLemma()))
								)) r.addGlobalTag("POS_MATCH", "TRUE");  
							else {   
								List<String> aPOS = r.getParts().get(r.getParts().size() - 1).getTags().get("UPOS");
								if (aPOS != null) {
									List<String> ctags = tags.get(k);
									Set<String> gPOS = rmap(ctags.get(0));
									for (String pos : aPOS)
										if (gPOS.contains(pos) && !(// Dirty hacks
											"niiden".equals(wtr.getWord().toLowerCase()) && "niisi".equals(r.getParts().get(0).getLemma())
										)) r.addGlobalTag("POS_MATCH", "TRUE");
								}
							}
						}
					}
				}
				if (depth > 1) {
					startOfSentenceInResults = j;
					SentenceData09 sd = new SentenceData09();
					String[] forms = new String[tokens.size() + 1];
					forms[0] = IOGenerals.ROOT;
					String[] lemmas = new String[forms.length];
					lemmas[0] = IOGenerals.ROOT_LEMMA;
					String[] poss = new String[forms.length];
					poss[0] = IOGenerals.ROOT_POS;
					String[] feats = new String[forms.length];
					feats[0] = IOGenerals.EMPTY_FEAT;
					for (int k = 1; k < forms.length; k++) {
						WordToResults wtr = ret.get(startOfSentenceInResults++);
						List<String> ctags = tags.get(k - 1);
						forms[k] = wtr.getWord();
						lemmas[k] = wtr.getWord();
						feats[k] = "_";
						poss[k] = "_";
						for (Result r : wtr.getAnalysis())
							if (!r.getParts().isEmpty() && r.getGlobalTags().containsKey("POS_MATCH")) {
								StringBuilder sb = new StringBuilder();
								for (WordPart p : r.getParts()) {
									sb.append(p.getLemma());
									sb.append('|');
								}
								sb.setLength(sb.length() - 1);
								lemmas[k] = sb.toString();
								poss[k] = ctags.get(0);
								sb.setLength(0);
								for (int l = 1; l < ctags.size(); l++) {
									sb.append(ctags.get(l));
									sb.append('|');
								}
								sb.setLength(sb.length() - 1);
								feats[k] = sb.toString();
							}
					}
					sd.init(forms);
					sd.setLemmas(lemmas);
					sd.setPPos(poss);
					sd.setFeats(feats);
					SentenceData09 out;
					synchronized (fiparser) {
						out = fiparser.parse(sd, fiparser.params, false, fiparser.options);
					}
					startOfSentenceInResults = j;
					for (int k = 0; k < out.forms.length; k++) {
						WordToResults wtr = ret.get(startOfSentenceInResults++);
						for (Result r : wtr.getAnalysis())
							if (r.getGlobalTags().containsKey("POS_MATCH")) {
								r.addGlobalTag("HEAD", "" + (j + out.pheads[k]));
								r.addGlobalTag("DEPREL", out.plabels[k]);
								
							}
					}
				}
			}
		}
		for (WordToResults wtr : ret) {
			List<Result> bestResult = new ArrayList<Result>();
			boolean POS_MATCH = false;
			boolean FIRST_LETTER_MATCH = false;
			float cw = Float.MAX_VALUE;
			ObjectLongMap<String> fMap = getFrequencyMap(lang);
			int guessCount = 0;
			long frequency = 0;
			for (Result res : wtr.getAnalysis()) {
				StringBuilder lemma = new StringBuilder();
				for (WordPart p : res.getParts()) {
					if (fMap.containsKey(p.getLemma())) p.addTag("BASEFORM_FREQUENCY", ""+fMap.get(p.getLemma()));
					lemma.append(p.getLemma());
				}
				int ngc = 0;
				List<String> gc = res.getGlobalTags().get("GUESS_COUNT");
				if (gc!=null) ngc=Integer.parseInt(gc.get(0));
				long myFrequency = fMap.getOrDefault(lemma.toString(), 0);
				if (myFrequency!=0) res.addGlobalTag("BASEFORM_FREQUENCY", ""+myFrequency);
				if (!FIRST_LETTER_MATCH || res.getGlobalTags().get("FIRST_IN_SENTENCE")!=null || res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0)) {
					if (POS_MATCH) {
						if (res.getGlobalTags().containsKey("POS_MATCH")) {
							if (res.getWeight() < cw) {
								bestResult.clear();
								bestResult.add(res);
								cw = res.getWeight();
								guessCount=ngc;
								frequency = myFrequency;
								FIRST_LETTER_MATCH = res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0);
							} else if (res.getWeight() == cw) {
								if (myFrequency>frequency) {
									bestResult.clear();
									bestResult.add(res);
									guessCount=ngc;
									frequency = myFrequency;
									FIRST_LETTER_MATCH = res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0);
								} else if (myFrequency==frequency) {
									if (ngc>guessCount) {
										guessCount=ngc;
										bestResult.clear();
									}
									if (ngc==guessCount)
										bestResult.add(res);
								}
							}
						}
					} else if (res.getGlobalTags().containsKey("POS_MATCH")) {
						POS_MATCH = true;
						bestResult.clear();
						bestResult.add(res);
						cw = res.getWeight();
						guessCount=ngc;
						frequency = myFrequency;
						FIRST_LETTER_MATCH = res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0);
					} else if (res.getWeight() < cw) {
						bestResult.clear();
						bestResult.add(res);
						cw = res.getWeight();
						guessCount=ngc;
						frequency = myFrequency;
						FIRST_LETTER_MATCH = res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0);
					} else if (res.getWeight() == cw) {
						if (myFrequency>frequency) {
							bestResult.clear();
							bestResult.add(res);
							guessCount=ngc;
							frequency = myFrequency;
							FIRST_LETTER_MATCH = res.getParts().get(0).getLemma().charAt(0)==wtr.getWord().charAt(0);
						} else if (myFrequency==frequency) {
							if (ngc>guessCount) {
								guessCount=ngc;
								bestResult.clear();
							}
							if (ngc==guessCount)
								bestResult.add(res);
						}
					}
				}
			}
			for (Result res : bestResult)
				res.addGlobalTag("BEST_MATCH", "TRUE");
		}
		return ret;
	}

	@Override
	public List<WordToResults> analyze(String str, Locale lang, List<String> inflections, boolean segmentBaseform, boolean guessUnknown, boolean segmentUnknown, int maxEditDistance) {
		return analyze(str, lang, inflections, segmentBaseform, guessUnknown, segmentUnknown, maxEditDistance, 2);
	}

	@Override
	public String inflect(String string, List<String> inflections, boolean segments, boolean baseform, boolean guessUnknown, int maxEditDistance, Locale lang) {
		StringBuilder ret = new StringBuilder();
		for (WordToResults part : analyze(string, lang, inflections, false, guessUnknown, false, maxEditDistance, 0)) {
			String inflected = getBestInflection(part, lang, segments, baseform);
			if (!inflected.isEmpty())
				ret.append(inflected);
			else ret.append(part.getWord());
			ret.append(' ');
		}
		return ret.toString().trim();
	}
	
	public RecognitionResult recognize(String str, Locale lang) {
                if (!supportedLocales.contains(lang)) return super.recognize(str, lang);
		Transducer tc = getTransducer(lang, "analysis", analysisTransducers);
		Tokenizer t = getTokenizer(lang);
		int recognized = 0;
		int unrecognized = 0;
		for (String sentence : getSentenceDetector(lang).sentDetect(str))
			outer: for (String label : t.tokenize(sentence)) {
				for (Transducer.Result tr : tc.analyze(label))
					if (!tr.getSymbols().isEmpty()) {
						recognized++;
						continue outer;
					}
				unrecognized++;
			}
		return new RecognitionResult(recognized,unrecognized);
	}
	
	public Collection<String> split(String str, Locale lang) {
		if (!supportedLocales.contains(lang)) return super.split(str,lang);
		return Arrays.asList(getSentenceDetector(lang).sentDetect(str));
	}
	
	@Override
	public Collection<Locale> getSupportedSplitLocales() {
		return supportedLocales;
	}

	public Collection<String> tokenize(String str, Locale lang) {
		if (!supportedLocales.contains(lang)) return super.tokenize(str,lang);
		return Arrays.asList(getTokenizer(lang).tokenize(str));
	}

	@Override
	public Collection<Locale> getSupportedTokenizationLocales() {
		return supportedLocales;
	}
	
	public static void main(String[] args) {
		final CombinedLexicalAnalysisService las = new CombinedLexicalAnalysisService();
		print(las.analyze("Astun aurinkoiselle Vironkadulle aivot ja sydän kiivaasti tykyttäen.", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.exit(0);
		print(las.analyze("  Helsingissä   oli kylmää... , dm upunki. Ystäväni J.W. Snellman juoksi pitkään pakoon omituisia elikoita, jotka söivät hänen kädestään?!", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.out.println(las.baseform("twiittasi", new Locale("fi"), false, false,0));
		System.out.println(las.baseform("twiittasi", new Locale("fi"), true, true,0));
		System.out.println(las.baseform("Leh>tim»ehen",new Locale("fi"),false,true,2));
		System.out.println(las.baseform("Helsingin", new Locale("fi"), false, true, 2));
		System.out.println(las.baseform("Pariisi", new Locale("fi"), false, true, 2));
		System.out.println(las.baseform("sup Pariisi", new Locale("fi"), false, true, 2));
		System.out.println(las.baseform("oli Pariisi", new Locale("fi"), false, true, 2));
		System.out.println(las.baseform("Tulemana Lauwantaina j. p. ulosannetaan N:o 47.\n\nO U L US A. Präntätty Barckin tykönä.", new Locale("fi"),false,true,2));
		for (Locale l :las.getSupportedBaseformLocales()) 
			System.out.println(l+": "+las.baseform("Turussa ja Helsingissä\n\n on kuin onkin aivoja",l,false,true,2));
		print(las.analyze("Helsingissä oli kylmää. Juoksin pitkään.", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.out.println(las.baseform("Ter>vo-»uainaj»n",new Locale("fi"),false,true,2));
		System.out.println(las.baseform("juoksettumise!sa", new Locale("fi"),false,false,0));
		System.out.println(las.baseform("juoksettumise!sa", new Locale("fi"),false,false,1));
		System.out.println(las.baseform("juoksettumise!sa", new Locale("fi"),false,false,1));
		System.out.println(las.baseform("juoksettumise!sa", new Locale("fi"),false,false,2));
		System.out.println(las.baseform("niiden",new Locale("fi"),false,true,0));
		print(las.analyze("niiden kuin", new Locale("fi"),Collections.EMPTY_LIST,false,true,false,2));
		print(las.analyze("spårassa", new Locale("fi"),Collections.EMPTY_LIST,false,true,false,2));
		print(las.analyze("spårassa", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,2));
		System.out.println(las.baseform("Meide twiittaili spårassa IBM:n insinörtin kanssa devaamisesta kuin mikäkin daiju. Olin iha megapöhinöissä", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("Istuin spårassa.", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("baa", new Locale("fi"), false, true,0));
		System.out.println(las.analyze("baa", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0,2));
		System.out.println(las.analyze("twiittasi", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0,2));
		System.out.println(las.baseform("twiittasi", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("Me Aleksander Kolmas, Jumalan Armosta, koko Venäjänmaan Keisari ja Itsevaltias, Puolanmaan Zsaari, Suomen Suuriruhtinas, y.m., y.m., y.m. Teemme tiettäväksi: Suomenmaan Valtiosäätyjen alamaisesta esityksestä tahdomme Me täten armosta vahvistaa seuraavan rikoslain Suomen Suuriruhtinaanmaalle, jonka voimaanpanemisesta, niinkuin myöskin rangaistusten täytäntöönpanosta erityinen asetus annetaan:", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("twiittasin", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("twiittasi", new Locale("fi"), false, true,0));
		System.out.println(las.baseform("hän twiittasi", new Locale("fi"), false, true,0));
		System.out.println(las.analyze("hän twiittasi", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0,2));
		System.out.println(las.analyze("kiipesin puuhun", new Locale("fi"),Collections.EMPTY_LIST,false,true,true,0,2));
		System.out.println(las.baseform("ulkoasiainministeriövaa'at soitti fagottia", new Locale("fi"),true, true,0));
		System.out.println(las.analyze("ulkoasiainministeriövaa'at 635. 635 sanomalehteä luin Suomessa", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true, true, true,0));
		System.out.println(las.baseform("635. 635 Helsingissä ulkoasiainministeriöstä vastaukset sanomalehdet varusteet komentosillat tietokannat tulosteet kriisipuhelimet kuin hyllyt", new Locale("fi"),true, true,0));
		System.out.println(las.hyphenate("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(las.recognize("sanomalehteä luin Suomessa", new Locale("fi")));
		System.out.println(las.recognize("The quick brown fox jumps over the lazy cat", new Locale("la")));
		System.out.println(las.recognize("The quick brown fox jumps over the lazy cat", new Locale("de")));
		System.out.println(las.recognize("The quick brown fox jumps over the lazy cat", new Locale("myv")));
		System.out.println(las.recognize("The quick brown fox jumps over the lazy cat", new Locale("en")));
		System.out.println(las.recognize("The quick brown fox jumps over the lazy cat", new Locale("mrj")));
		System.out.println(las.recognize("Eorum una, pars, quam Gallos obtinere dictum est, initium capit a flumine Rhodano, continetur Garumna flumine, Oceano, finibus Belgarum, attingit etiam ab Sequanis et Helvetiis flumen Rhenum, vergit ad septentriones.", new Locale("la")));
		System.out.println(las.inflect("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), true, true, true,0, new Locale("fi")));
		System.out.println(las.inflect("maatiaiskanan sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }), false, false, false,0, new Locale("fi")));		
		//		las.analyze("Maanmittauksessa Swardlucken kiintopiste on kuin onkin maastoon pysyvästi merkitty maastonkohta, jonka sijainti tai korkeus on tarkasti määritetty suhteessa muihin kiintopisteisiin tai tiettyyn koordinaatistoon. Merkkinä voi olla esimerkiksi pultti kalliossa tai kadun pinnassa, maston tai majakan huippu, tai valtakunnan rajapyykki. Kiintopisteitä käytetään lähtökohtana, kun määritetään muiden maastonkohtien sijainteja tai korkeuksia. Kiintopiste voi olla tasokiintopiste, jolloin sen paikka tunnetaan tarkasti leveys- ja korkeuspiirien suhteen, korkeuskiintopiste, jolloin sen paikka tunnetaan pystysuunnassa suhteessa keskimääräiseen merenpinnan tasoon tai teoreettiseen geoidiin, tai se voi olla molempia samanaikaisesti. Suomessa valtakunnallista kiintopisterekisteriä pitää maanmittauslaitos. Kunnilla on omia kiintopisteverkkojaan. Tasokiitopisteiden mittauksissa on aiemmin käytetty kolmiomittausta, kun taas nykyään käytetään erityisesti maanmittaustarkoitukseen suunniteltuja, tavallista tarkempia satelliittipaikannusmenetelmiä. Korkeuskiintopisteet on puolestaan määritetty vaaitsemalla. Mittaustavoista johtuen kiintopisteen paikan epävarmuus on suurimmillaan korkeudessa. Vaakatasossa tarkkuus on parhaimmillaan millimetriluokkaa, samalla kun pystysuuntaan harvoin päästään laajemmalla alalla edes alhaisiin senttimetreihin, satelliittejakaan hyödyntäen. Tamman tiineys kestää noin 11 kalenterikuukautta. Tiineyden alussa tammaa voidaan kouluttaa aivan tavalliseen tapaan. Kun tamman vatsa alkaa kasvaa, ja sen paino alkaa nousta, on käytettävä maalaisjärkeä. Tammalta ei saa vaatia liikaa. On tärkeää, että kantava tamma saa olla paljon ulkona ja liikkua mielihalujensa mukaan. Eräät tammat saattavat muuttua hieman ärtyisiksi tiineyden aikana: asiat, jotka ovat olleet niille yhdentekeviä aikaisemmin, ovatkin nyt vaikeampia. Tammaa saattaa esimerkiksi ärsyttää, kun sitä harjataan vatsan alta tai kun satulavyötä kiristetään. Jotkut tammat saattavat muuttua levottomiksi tiineyden edetessä. Kaikki tämä on aivan normaalia, joten syytä huoleen ei ole. Tämä kaikki on selvä merkki siitä, että tamma tietää, mikä sitä odottaa. Eräät tammat ovat tiineyden aikana niin ärtyisiä, että niiden vatsa pitäisi jättää rauhaan. Kun tamma on kantava, täytyy ajatella tamman ja kohdussa olevan varsan terveyttä. Tiineelle tammalle ei kannata antaa tavallista rehua. Kantaville tammoille on tarkoitettu oma erityisrehu, jota kaikki rehuntoimittajat myyvät. Muutama päivä ennen synnytystä jo aiemmin varsoneiden tammojen utareisiin ilmestyy ns. vahatapit. Siitä tietää, että varsominen on lähellä. Selkie on kylä Kontiolahdella. Se sijaitsee kunnan itäosassa Ilomantsintieltä erkanevan Jakokoski-Heinävaara -tien eli Mönnin-Selkien maantien (yhdystie 5100) varrella. Kylän naapurikyliä ovat Heinävaara ja Mönni. Asukkaita kylällä on noin 270.[1] Selillä on kyläkoulu sekä oma evankelisluterilainen hautausmaakappeli Selkien-Mönnin hautausmaalla. Alakoulussa on tällä hetkellä noin 35 oppilasta Seliltä ja Mönnistä. Kylästä on arkipäivisin linja-autoyhteys Kontiolahden ja Joensuun suuntiin. Liikuntatiloja on Palotalon nykyaikaisessa urheiluhallissa. Mustavaaran laskettelukeskus sijoittuu kylän etelälaidalle. Selkie-Lehtoi on nauhamainen kyläkokonaisuus, joka ryhmittyy korkealle vaaralinjalle vanhan Tohmajärven maantien varteen. Tie seuraa paikoitellen 1600-luvun ensimmäistä, rinteille kaskiviljelyn tuoman asutuksen synnyttämää kylätien linjaa. Vaarat nousevat paikoin yli 200 metrin korkeudelle merenpinnasta. Kylältä on toistaiseksi laajoja selkeitä näkymiä ympäristöön: suora näköyhteys jopa Joensuun keskustaan, Pielisjoelle ja Kolille asti. Möykynmäki on seudun tieliikenneympäristöistä paikallisesti koettavin. Kylän nimi \"Selkien kylä\" taipuu muodossa \"Selillä, Seliltä, Selille\"; Lehtoin kylä (varhain oma kylänsä) \"Lehtoilla, Lehtoilta, Lehtoille\". Nykyinen kirjoitusasu on todennäköisesti peräisin Venäjän vallan ajalta, ja tämä on edesauttanut nykyisiäkin poikkeavia kielellisiä variaatioita. Virallisissa yhteyksissä sallitaan muodollisesti \"Selkiellä\" jne. Selkien pohjoisin osa kuuluu valtakunnallisesti merkittäviin rakennettuihin kulttuuriympäristöihin. Samoin Selkien-Lehtoin-Heinävaaran kyläalue on yksi valtioneuvoston periaatepäätöksen mukaisista valtakunnallisesti arvokkaista maisema-alueista (koko maassa 156). Muiden Pohjois-Karjalan vaarakylien tapaan Selkie on osa ympäristöministeriön vuonna 1992, Suomen 75-vuotisjuhlavuonna, julkistamia Suomen kansallismaisemia[3]. Historiallisen, kulttuuri- tai luonnonmaisemansa puolesta merkityksellisiksi kansallismaisemiksi on valittu yhteensä 27 väljää aluekokonaisuutta (maisematyyppiä) ympäri Suomen. Luokittelulla ei ole juridista merkitystä. Selkien kyläalueeseen kuuluvat Selkien ja Lehtoin lisäksi muiden muassa Elovaara, Havukkavaara, Jukajoki ja Mustavaara. Kylän postitoimipaikka oli 81235 Lehtoi vuoden 2012 lopputalveen saakka. ", new Locale("fi"));
		new Thread() {
			@Override
			public void run() {
				System.out.println(las.analyze("sanomalehteä luin Suomessa", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }),true, true, true,0));
			}
		}.start();
		System.out.println(las.analyze("sanomalehteä luin Suomessa", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }),true, true, true,0));
		las.analyze("Siirry Eduskunta fi sivustollesvenska		ValtiopäiväasiatAsiasanastoAsiakirjatPöytäkirjatÄänestyksetKäsittelyvaihekaavioAsioiden valiokuntakäsittelyLausumatTilastot ja raportit                 » Johdanto» Vireilletuloasiakirjojen kuvaukset» Valiokunnan kannanotot» Päätösehdotukset» Ratkaisevaan käsittelyyn osallistuneet valiokunnan jäsenet» VASTALAUSE» Eduskunnan vastaus» HE 318/2010 vp» Asiakirjan painoasu» Rakenteinen asiakirja» Täysistunnon pöytäkirja» Päätöspöytäkirja» Asian käsittelytiedotLähetä linkki < Paluu  |  Sivun loppuunTarkistettu versio 2 1LaVM 40/2010 vp - HE318/2010 vpEduskunta on 11 päivänä tammikuuta2011 lähettänyt lakivaliokuntaan valmistelevastikäsiteltäväksi hallituksen esityksenlaiksi luvan saaneista oikeudenkäyntiavustajista ja eräiksisiihen liittyviksi laeiksi (HE 318/2010 vp) Valiokunnassa ovat olleet kuultavinaKirjallisen lausunnon ovat antaneetLisäksi Elinkeinoelämänkeskusliitto EK on toimittanut valiokunnalle kirjallisen kannanoton Esityksessä ehdotetaan säädettäväksilaki luvan saaneista oikeudenkäyntiavustajista  Lisäksi muutettaisiinasianajajista annettua lakia, valtion oikeusaputoimistoista annettualakia, oikeudenkäymiskaarta, oikeudenkäynnistä rikosasioissaannettua lakia, oikeusapulakia, lastensuojelulakia ja valtioneuvostonoikeuskanslerista annettua lakia Esityksen päätavoitteena on oikeudenkäyntienasianosaisten oikeusturvan ja asianmukaisen oikeudenhoidonedellytysten parantaminen oikeudenkäyntiasiamiesten ja-avustajien työn laatutasoa nostamalla  Tämä toteutettaisiin siten,että kaikki oikeudenkäyntiasiamiehet ja -avustajatsaatettaisiin ammattieettisten velvollisuuksien ja valvonnanpiiriin perustamalla muille oikeudenkäyntiasiamiehilleja -avustajille kuin asianajajille ja julkisille oikeusavustajilleuusi lupajärjestelmä  Luvat myöntäisiperustettava oikeudenkäyntiavustajalautakunta Luvan saanut oikeudenkäyntiavustaja olisi oikeudenkäyntiasiamiehenja -avustajan tehtävässään,samoin kuin tuomioistuimen määräykseentai avustajanmääräykseen perustuvassa tehtävässä,velvollinen noudattamaan asiallisesti samansisältöisiä ammattieettisiä sääntöjä kuinasianajajat ja julkiset oikeusavustajat Luvan saanut oikeudenkäyntiavustaja olisi mainituissatehtävissään oikeuskanslerin, maan yleisenasianajajayhdistyksen - toisin sanoen Suomen Asianajajaliiton - yhteydessä toimivanvalvontalautakunnan ja oikeudenkäyntiavustajalautakunnanvalvonnan alainen  Luvan saaneelle oikeudenkäyntiavustajallevoitaisiin määrätä vastaavatkurinpidolliset seuraamukset kuin asianajajalle ja julkiselle oikeusavustajalle Kurinpidollista seuraamusta koskevista päätöksistä olisimuutoksenhakumahdollisuus tuomioistuimeen Asianajajista annettuun lakiin ja valtion oikeusaputoimistoistaannettuun lakiin tehtäisiin uudesta lupajärjestelmästä johtuvatmuutokset  Valvontalautakunnan riippumattomuutta ja toiminnallistaerillisyyttä suhteessa Suomen Asianajajaliittoon vahvistettaisiin Valvontalautakuntaa laajennettaisiin yhdellä jaostolla,ja lautakuntaan otettaisiin jäseniksi myös luvan saaneitaoikeudenkäyntiavustajia Oikeudenkäymiskaarta muutettaisiin siten, että oikeudenkäyntiasiamiehenä tai-avustajana voisi yleisissä tuomioistuimissa yleensä toimia vainasianajaja, julkinen oikeusavustaja tai luvan saanut oikeudenkäyntiavustaja Avustajapakko otettaisiin käyttöönylimääräisessä muutoksenhaussakorkeimmassa oikeudessa Oikeudenkäynnistä rikosasioissa annetussa laissatarkoitetuksi puolustajaksi tai asianomistajan oikeudenkäyntiavustajaksisekä oikeusapulaissa tarkoitetuksi avustajaksi voitaisiin määrätä julkinenoikeusavustaja, asianajaja ja tietyin edellytyksin luvan saanutoikeudenkäyntiavustaja Lastensuojelulain muutoksella lupajärjestelmä ulotettaisiinmyös lastensuojeluasioihin hallinto-oikeudessa ja korkeimmassahallinto-oikeudessa Ehdotetut lait ovat tarkoitetut tulemaan voimaan vuoden 2013alusta  Vuoden siirtymäaikana lakien voimaantulostalukien oikeudenkäyntiasiamiehet ja -avustajat säilyttäisivät aiemmankelpoisuutensa Ehdotetulla lainsäädännöllä perustetaanuusi lupajärjestelmä oikeudenkäyntiasiamiehiä ja -avustajia(jäljempänä oikeudenkäyntiasiamies) varten Jatkossa oikeudenkäyntiasiamiehenä yleisissä tuomioistuimissavoi toimia yleensä vain asianajaja, julkinen oikeusavustajatai luvan saanut oikeudenkäyntiasiamies  Lupajärjestelmä ehdotetaanulotettavaksi myös hallinto-oikeudessa ja korkeimmassahallinto-oikeudessa käsiteltäviin lastensuojeluasioihin Lisäksi avustajapakko otetaan käyttöönkorkeimpaan oikeuteen tehtävissä tuomiovirhekanteluaja tuomion purkamista koskevissa ylimääräisenmuutoksenhaun hakemuksissa Uudistuksen päätavoitteena on oikeudenkäyntienasianosaisten oikeusturvan ja asianmukaisen oikeudenhoidon edellytystenparantaminen oikeudenkäyntiasiamiesten työn laatutasoa nostamalla Uudistuksen myötä kaikki oikeudenkäyntiasiamiehetsaatetaan ammattieettisten velvollisuuksien ja valvonnan piiriin Lisäksi lupajärjestelmän piiriin kuuluvienoikeudenkäyntiasiamiesten kelpoisuusehtoja tiukennetaanjonkin verran nykyisestä  Uusi lupajärjestelmä ei koskeasianajajia eikä julkisia oikeusavustajia, joiden kelpoisuusehdotovat nykyisellään riittävätja jotka jo ovat ammattieettisten velvollisuuksien javalvonnan piirissä Valiokunta toteaa, että prosessin asettamiin laatuvaatimuksiinja kuluttajansuojaa koskeviin näkökohtiin yleisissä tuomioistuimissakäsiteltävissä asioissa kiinnitettiinhuomiota jo vuonna 2002 toteutetun oikeudenkäyntiasiamiestenkelpoisuusvaatimusten tiukennusta tarkoittaneen uudistuksen yhteydessä (HE 82/2001 vp - LaVM22/2001 vp)  Sittemmin tuomioistuinlaitoksenkehittämiskomitea on tarkastellut syitä, joidenvuoksi oikeudenkäyntiasiamiehiä koskevaa sääntelyä tulisikomitean mielestä arvioida uudelleen ja tiukentaakelpoisuusvaatimuksia yleisissä tuomioistuimissa (Komiteanmietintö 2003:3) Komitean mukaan tavoitteena tulisi olla, että toisen asiaavoi ajaa tuomioistuimessa vain ammatillisesti pätevä jaammattieettisesti moitteeton asiamies, jonka toiminta on itsenäisenja riippumattoman valvonta- tai kurinpitoelimen kontrollin alaista Oikeudenkäyntiasiamiesten toimintaympäristö - tuomioistuimetja syyttäjälaitos - on ollut viime vuosinamerkittävien uudistusten kohteena  Siten on perusteltuatarkastella myös oikeudenkäyntiasiamiehiä,heille asetettavia vaatimuksia sekä heidän toimintaansakoskevia säännöksiä  Hallituksenesityksestä ilmenevistä syistä ja saamansaselvityksen perusteella valiokunta pitää esitystä tarpeellisenaja tarkoituksenmukaisena  Valiokunta puoltaa hallituksen esitykseensisältyvien lakiehdotusten hyväksymistä seuraavinhuomautuksin ja muutosehdotuksin  Lisäksi valiokunta ehdottaahyväksyttäväksi neljä uuttalakiehdotusta uudistuksesta johtuvien lakiteknisten viittaustenhuomioonottamiseksi Luvan saaneista oikeudenkäyntiavustajista ehdotetunlain 2 §:ssä säädetäänoikeudenkäyntiasiamiehen ja -avustajan kelpoisuusedellytyksistä Kun hakija täyttää luvan myöntämisen edellytykset,lupa oikeudenkäyntiasiamiehenä ja -avustajanatoimimiseen on myönnettävä hänelle Lupahakemusta ei voi hylätä tarkoituksenmukaisuusperusteilla Kelpoisuusehdot tiukentuvat jonkin verran nykyisestä siten,että oikeudenkäyntiasiamieheltä edellytetäänoikeustieteellisen tutkinnon suorittamisen lisäksi riittävää perehtyneisyyttä oikeudenkäyntiasiamiehenja -avustajan tehtäviin  Perehtyneisyyden voi saavuttaatyökokemuksen kautta tai suorittamalla asianajajatutkinnon Riittävä perehtyneisyys on esimerkiksi henkilöllä,joka on suorittanut tuomioistuinharjoittelun tai toiminutvähintään vuoden ajan syyttäjäntehtävissä  Riittävä perehtyneisyyson myös henkilöllä, joka lakimiestutkinnonsuorittamisen jälkeen on toiminut vähintäänvuoden ajan muussa oikeudenkäyntiasiamiehen ja -avustajantehtävään perehdyttävässä työssä  Tällainenvoi olla esimerkiksi avustavan lakimiehen työ asianajo-tai lakiasiaintoimistossa silloin, kun siihen säännönmukaisestikuuluu oikeudenkäyntiasioihin liittyvää avustamista  Lisäksiedellytetään, että henkilö onrehellinen ja sopiva oikeudenkäyntiasiamiehen ja -avustajantehtävään ja ettei hän ole konkurssissaeikä hänen toimintakelpoisuuttaan ole rajoitettu Valiokunta pitää oikeudenkäyntiasiamieheltä vaadittaviakelpoisuusehtoja asianmukaisina ja perusteltuina  Esityksen tarkoituksenaon nimenomaan nostaa oikeudenkäyntiasiamiesten työnlaatutasoa ja siten parantaa asianosaisten oikeusturvaa ja asianmukaisenoikeudenhoidon edellytyksiä  Valiokunnan mielestä kelpoisuusehtojaei ole syytä lieventää esitetystä Valiokunta ei toisaalta myöskään näeaihetta tiukentaa esitettyjä kelpoisuusehtoja, vaan pitää niitä riittävinä muutoksinanykytilaan nähden  Huomattava kelpoisuusehtojen tiukentaminensaattaisi esimerkiksi vaikeuttaa alalle pääsyä Tässä yhteydessä valiokunta myöstoteaa, että oikeusministeriö on vahvistanut asianajajatutkinnontutkintojärjestyksen muutoksen, jota sovelletaan 1 3 2011järjestettäviin asianajajatutkintoihin  Uudentutkintojärjestyksen mukaan asianajajatutkintoon voi ilmoittautuaoikeusnotaarin tutkinnon suorittanut henkilö Siten oikeustieteen maisterin tutkintoa opiskeleva henkilö voioikeusnotaarin tutkinnon suoritettuaan jo opiskeluaikana suorittaaasianajajatutkinnon ja oikeustieteen maisteriksi valmistuttuaanvälittömästi hakea tässä tarkoitettualupaa Luvan saaneista oikeudenkäyntiavustajista ehdotetunlain 8 §:ssä säädetäänlupajärjestelmän piiriin kuuluvien oikeudenkäyntiasiamiesten ammattieettisistä velvollisuuksista Ehdotuksen mukaan oikeudenkäyntiasiamiehen tulee rehellisestija tunnollisesti täyttää hänelleuskotut oikeudenkäyntiasiamiehen ja -avustajantehtävät  Lisäksi pykälässä luetellaanoikeudenkäyntiasiamiehen omaan asiakkaaseen, asiakkaan vastapuoleen,viranomaisiin ja asiakkaan vastapuolen asiamieheen liittyvätkeskeisimmät velvollisuudet  Muista kuin luettelossa nimenomaisestimainituista velvollisuuksista, kuten myös velvollisuuksientarkemmasta sisällöstä, on mahdollistahakea johtoa asianajajia velvoittavasta hyvästä asianajajatavastasekä sitä koskevasta soveltamiskäytännöstä Hallituksen esityksen perusteluista ilmenee, että esityksennimenomaisena tarkoituksena on säätää oikeudenkäyntiasiamiehilleoma velvollisuuksia koskeva säännös sensijaan, että esimerkiksi viitattaisiin Suomen Asianajajaliitonhyväksymiin hyvää asianajajatapaa koskeviinohjeisiin  Esityksen perusteluissa (s  19) mainituistasyistä valiokunta pitää esityksessä omaksuttuasääntelyratkaisua perusteltuna  Luvan saaneetoikeudenkäyntiasiamiehet ovat puheena olevan 8 §:nnojalla velvollisia noudattamaan asiallisesti samansisältöisiä ammattieettisiä velvollisuuksiakuin asianajajat ja julkiset oikeusavustajat nykyisin Luvan saaneiden oikeudenkäyntiasiamiesten tulee noudattaalaissa säädettyjä velvollisuuksiaanhoitaessaan oikeudenkäyntiasiamiehen ja -avustajantehtäviä  Näihin sisältyvätpaitsi varsinainen edustaminen ja esiintyminen tuomioistuimessa,myös esimerkiksi oikeudenkäyntiä valmistelevattoimet, kuten asianosaisen avustaminen rikosasian esitutkinnassa Oikeudenkäyntiasiamiehen tehtäviin liittymättömienoikeudellisten palvelujen tarjoaminen ei sisälly velvollisuudenpiiriin  Oikeudenkäyntiasiamies on kuitenkin velvollinennoudattamaan 8 §:ssä säädettyjä velvollisuuksiasellaisessa muussa tehtävässä, jonkahän on saanut tuomioistuimen määräyksenperusteella tai johon hänet on määrätty oikeusapulaissa(257/2002) tarkoitetuksi avustajaksi Tällainen muu tehtävä voi olla esimerkiksituomioistuimen määräykseen perustuvan pesänjakajantehtävän hoitaminen Selvyyden vuoksi valiokunta toteaa, että oikeudenkäyntiasiamiehenvelvoite noudattaa mainitussa 8 §:ssä säädettyjä velvollisuuksiaan eikoske vain niitä asioita, joissa häneltä edellytetääntässä tarkoitettua lupaa, vaan hänentulee noudattaa niitä kaikissa hoitamissaan oikeudenkäyntiasiamiehenja -avustajan tehtävissä  Valiokunnan mielestä eiole ajateltavissa, että esimerkiksi ns  summaarisessa riita-asiassa,jossa oikeudenkäyntiasiamieheltä ei edellytetä tässä tarkoitettualupaa, velvoite noudattaa mainittuja velvollisuuksia syntyisi vasta,jos hänen hoitamansa asia muuttuu kesken prosessin vastaajan kiistämisenjohdosta riitaiseksi Luvan oikeudenkäyntiasiamiehenä ja -avustajanatoimimiseen myöntää valtioneuvoston asettamaoikeudenkäyntiavustajalautakunta  Oikeudenkäyntiasiamiestentoimintaa valvovat edellä mainittu oikeudenkäyntiavustajalautakunta,Asianajajaliiton yhteydessä toimiva valvontalautakuntasekä valtioneuvoston oikeuskansleri  Johdonmukaisesti senkanssa, mitä edellä on todettu oikeudenkäyntiasiamiehen velvollisuuksista,valvonta koskee ainoastaan hänen toimintaansa oikeudenkäyntiasiamiehen ja-avustajan tehtävässä, tuomioistuimenmääräykseen perustuvassa tehtävässä jaoikeusapulaissa tarkoitetun avustajan tehtävässä Esityksen mukaan oikeudenkäyntiasiamiehet ovat oikeuskanslerinvalvonnan alaisia vastaavasti kuin asianajajat ja julkiset oikeusavustajat ovatjo nykyisin  Oikeuskanslerilla on oikeus panna valvonta-asia vireille,jos hän katsoo, että oikeudenkäyntiasiamieslaiminlyö velvollisuutensa  Oikeudenkäyntiasiamieson velvollinen antamaan oikeuskanslerille ne tiedot ja selvitykset,jotka ovat tarpeen tälle valvontatehtävän suorittamiseksi Valvontalautakunnan ja oikeudenkäyntiavustajalautakunnanvalvonta perustuu pääsääntöisestikanteluihin  Kantelujen käsitteleminen ja ratkaiseminenkuuluvat lähtökohtaisesti valvontalautakunnalle Valvontalautakunta voi määrätä kurinpidollisenaseuraamuksena huomautuksen tai varoituksen  Vakavimmista rikkeistä voiseurata enintään 15 000 euron seuraamusmaksutai luvan peruuttaminen, joista päättää oikeudenkäyntiavustajalautakunta Valvonta-asian ratkaisuun voi hakea muutosta valittamalla Helsinginhovioikeuteen, joka jo nykyisin käsittelee asianajajistaannetun lain (496/1958) mukaiset valitukset Asianajajaliitosta erotetulle tai lupansa menettäneellevoidaan myöntää uusi lupa aikaisintaankolmen vuoden kuluttua Valiokunta on pohtinut oikeudenkäyntiasiamiehen valvontamenettelynerilaisia järjestämistapoja  Valiokunnan mielestä esitettyä valvontarakennettavoidaan puoltaa ottaen huomioon esityksen lähtökohta,jonka mukaan oikeudenkäyntiasiamiehet ovat velvollisianoudattamaan asiallisesti samansisältöisiä ammattieettisiä sääntöjä kuinasianajajat ja julkiset oikeusavustajat  Ehdotukset valvontalautakunnanroolista ja muutoksenhaun järjestämisestä yhdenmukaisestiasianajajia ja julkisia oikeusavustajia koskevien valvonta-asioidenkanssa ovat perusteltuja yhteneväisen ratkaisukäytännönturvaamisen kannalta  Valiokunta pitää kuitenkintarpeellisena, että valvonnan toimivuutta seurataan ja arvioidaan,tuleeko järjestelmää jatkossa kehittää Kun valvontalautakunnalle annetaan oikeudenkäyntiasiamiehiä koskeviavalvontatehtäviä, on tarpeellista, että valvontalautakunnanriippumattomuutta ja toiminnallista erillisyyttä Asianajajaliitostavahvistetaan  Esityksessä ehdotetaankin, että valvontalautakunnankokoonpanoa laajennetaan ja että siihen otetaan jäseniksiasianajajien ja laissa tarkemmin määriteltyjenmuiden lakimiesten lisäksi myös luvan saaneitaoikeudenkäyntiasiamiehiä  Tässä yhteydessä onsyytä tähdentää, että valvontalautakunnanjäsenet toimivat tehtävässääntuomarin vastuulla ja asioita valmistelevan valvontayksiköntoimihenkilöihin sovelletaan rikosoikeudellista virkavastuutakoskevia säännöksiä  Lisäksi valvonta-asioidenkäsittelyn järjestämiseen liittyvä yksiperiaatteellinen lähtökohta on se, että valvonta-asioidenohjautuminen valvontalautakunnan eri jaostojen kesken tapahtuu tuomioistuintentapaan sattumanvaraisesti, jolloin etukäteen ei ole tiedossa,ketkä tiettyä valvonta-asiaa käsittelevät Myös tämä osaltaan turvaa käsittelynobjektiivisuutta Ottaen huomioon, että luvan saaneiden oikeudenkäyntiasiamiestenosuus valvontalautakunnan jäsenistöstä onsuhteellisen pieni - kaksi kahdestatoista jäsenestä - heidänasemaansa on kuitenkin syytä vahvistaa  Sopusoinnussa sen edellä mainitunperiaatteellisen lähtökohdan kanssa, että asiatjakautuvat jaostojen kesken sattumanvaraisesti, voidaan pitää järjestelyä, jossaoikeudenkäyntiasiamiestä koskevaa valvonta-asiaakäsiteltäessä puheenjohtajana valvontalautakunnanjaostossa toimii aina muu kuin asianajajakuntaan kuuluva jäsen Valiokunta ehdottaa jäljempänä 1  lakiehdotuksen11 §:n täydentämistä tätä tarkoittavallasäännöksellä Lupajärjestelmä yleisissä tuomioistuimissakäsiteltävissä asioissa on lähtökohtaisestiyleinen  Lupajärjestelmä koskee kaikkia yleisiä tuomioistuimiaja kaikkia niissä käsiteltäviä asiaryhmiä,jollei jonkin asiaryhmän osalta ole toisin säädetty Merkitystä ei siten ole esimerkiksi sillä, onkohenkilön toiminta ammattimaista vai satunnaista, sillä myösyksittäinen asia on hoidettava ammattitaitoisesti Lupajärjestelmän ulkopuolelle esitetäänrajattavaksi asianosaiseen työ- tai virkasuhteessa olevatoikeudenkäyntiasiamiehet sekä työmarkkinajärjestöjenpalveluksessa olevat lakimiehet  Esityksen perusteluissa todetuistasyistä (s  17 ja 18) valiokunta pitää rajauksiaperusteltuina Asian valiokuntakäsittelyn yhteydessä on noussutesiin kysymys siitä, tulisiko lakiin sisältyvä poikkeustyö- ja virkasuhteessa olevista asiamiehistä ulottaakoskemaan myös samassa konsernissa tai yritysryhmässä työskenteleviä lakimiehiä Valiokunta toteaa esityksen lähtökohtana olevan,että lupajärjestelmän toteuttaminenyleisissä tuomioistuimissa käsiteltävissä asioissaon vahva pääsääntö jaettä kynnys pääsäännöstä poikkeamisilleasettuu tästä syystä varsin korkealle Järjestelmä on kaikkien toimijoiden kannalta sitä selkeämpi,mitä vähemmän poikkeuksia on Ehdotusta työ- ja virkasuhteessa olevasta asiamiehestä onesityksessä perusteltu muun ohessa sillä, että työntekijä onjoka tapauksessa työnantajan valvonnan alainen ja että työnantaja onvastuussa työntekijän mahdollisesti aiheuttamastavahingosta  Valiokunnan käsityksen mukaan kumpikaan mainituistaperusteista ei välttämättä sovelluläheskään kaikkiin eri yritysryhmittymiin Tämän vuoksi edellä tarkoitettua konsernilakimiespoikkeustaei valiokunnan mielestä voida asiallisesti samastaa esityksessä ehdotettuuntyö- ja virkasuhteessa olevia asiamiehiä koskevaanpoikkeukseen Samassa konsernissa tai yritysryhmässä työskenteleviä koskevaanpoikkeukseen liittyisi myös käytännönongelmia  Edes osakeyhtiölain (624/2006)mukaisen konserniaseman selvittäminen ja määrittelyei valiokunnan käsityksen mukaan aina ole yksinkertaista Tuomioistuimilla on velvollisuus viran puolesta kontrolloida se, että asianomainenoikeudenkäyntiasiamies on kelpoinen toimimaan asiamiehenä Jo tähän nähden on syytä pyrkiä välttämäänkovin tulkinnanvaraisia säännöksiä taisäännöksiä, jotka edellyttävätlaajaa asiantilan selvittämistä Esityksessä ehdotetaan luovuttavaksi voimassa olevastapelkkään sukulaisuussuhteeseen perustuvasta oikeudestatoimia oikeudenkäyntiasiamiehenä  Valiokunta katsoo,että lähisukulaista tai aviopuolisoa koskevanpoikkeuksen säilyttämiselle ei nyky-yhteiskunnassaole enää riittäviä perusteita,ja säännöksestä luopumista voidaansiten pitää perusteltuna Ehdotus merkitsee myös sitä, että lähiomainenei ilman säädettyä lupaa voi toimia oikeudenkäyntiasiamiehenä,vaikka hän olisi lakimies  Käytännössä voisyntyä kohtuuttomalta tuntuvia tilanteita esimerkiksi silloin,kun oikeustieteellisen tutkinnon suorittanut henkilö omaaasiaa ajaessaan edustaisi samalla myös puolisoaan ja lapsiaanesimerkiksi yhteistä asuntoa koskevassa riita-asiassa Vastaavanlainen tilanne voisi syntyä myösesimerkiksi jakamattomassa kuolinpesässä pesänosakkaiden kesken Valiokunta pitää edellä mainittujanäkökohtia ymmärrettävinä Lupaedellytyksen väljentäminen siten, että laissaannettaisiin edellä kuvatun tyyppisissä tilanteissaoikeus oikeudenkäyntiasiamiehenä toimimiseen muullekinlakimiehelle kuin asianajajalle, julkiselle oikeusavustajalle tailuvan saaneelle oikeudenkäyntiasiamiehelle, ei kuitenkaanole aivan ongelmaton  Valiokunta viittaa siihen, mitä edellä ontodettu lupajärjestelmän ehdottomasta pääsäännöstä ja tulkinnanvaraistenpoikkeusten välttämisestä  Selvää eimyöskään ole se, missä määrintällaiselle säännökselle olisitodellista tarvetta  Varsin monet yksinkertaiset tuomioistuinasiatvoidaan nykyään käsitellä pelkästäänkirjallisessa menettelyssä, jolloin ei ole tarvetta asiamiehen käyttämiseen Istuntokäsittelyä vaativat asiat ovat taas useinriitaisia, jolloin aviopuolison tai lähisukulaisen ei välttämättä olemahdollista toimia puolisonsa taikka vanhempansa tai lapsensa asiamiehenä joerilaisten intressiristiriitojen vuoksi  Mainitut riitaiset asiatovat usein myös siinä määrinvaativia, että ne yleensä annetaan ammatikseenasianajoa harjoittavan asiamiehen hoidettavaksi  Lisäksivoidaan myös todeta, että kelpoisuusehdot täyttävä lähiomainenvoi tarvittaessa toimia oikeudenkäyntiasiamiehenä hankittuaanlaissa säädetyn luvan  Valiokunta ei näinollen pidä mainitunlaisen poikkeuksen säätämistä tässä yhteydessä aiheellisena Myös esimerkiksi sellainen peruste, joka mahdollistaisilupaedellytyksestä poikkeamisen silloin, kun kysymys olisisatunnaisesta asianajosta, olisi valiokunnan mielestä tulkinnanvarainen Lisäksi tuomioistuimen voisi olla mahdotonta kontrolloidasitä, milloin kyse on vain satunnaisesta asiamiehenä toimimisesta Valiokunnan näkemyksen mukaan myös se, että lupajärjestelmästä poikkeaminenmahdollistettaisiin tuomioistuimen harkintaan perustuvallapäätöksellä, olisi käytännössä edellä kuvatuistasyistä ongelmallinen ja voisi etenkin laajemmin käytettynä merkitä tuomioistuimille huomattavaalisärasitetta  Seurauksena voisi myös olla, että ensinkäytäisiin oikeutta - mahdollisesti erimuutoksenhakuasteissa - siitä esikysymyksestä,kuka voi toimia oikeudenkäyntiasiamiehenä  Tämä eiolisi linjassa järjestelmän selkeyttä jaoikeudenhoidon parantamista koskevien tavoitteiden kanssa Valiokunta puoltaa siten sääntelyä ehdotetussamuodossaan  Järjestelmän toimivuutta on kuitenkinhyvä myös näiltä osin seurata Saatujen kokemusten perusteella voidaan myöhemmin arvioidamahdollisia kehittämistarpeita Selvyyden vuoksi valiokunta vielä toteaa, että ehdotettulainsäädäntö ei tarkoita sitä,ettei kelpoisuusvaatimukset täyttävänoikeudenkäyntiasiamiehen rinnalla voisi käyttää erityisasiantuntemustatai kielitaitoa edellyttävissä asioissa henkilöä,joka ei ole asianajaja, julkinen oikeusavustaja tai luvan saanutoikeudenkäyntiasiamies  Tällainen menettely onmahdollinen jo nykyisen lain mukaan Valiokunta pitää tarpeellisena lupajärjestelmän käyttöönottoahallinto-oikeudessa ja korkeimmassa hallinto-oikeudessa käsiteltäviinlastensuojeluasioihin  Lasten huostaanottoa ja muuta lastensuojeluakoskevissa asioissa oikeusturvavaatimukset ovat korostuneita  Asioidenlaatu edellyttää myös asianosaisten asiamiehiltä ja avustajiltaoikeudellista ammattitaitoa ja ammattieettistä toimintaa Lisäksi vuoden 2008 alusta voimaantulleella lastensuojelulaillauudistettiin päätöksentekojärjestelmää siten,että tahdonvastaisia huostaanottoasioita koskevat päätöksettehdään kuntatason sijasta ensivaiheessa hallinto-oikeuksissa Korkein hallinto-oikeus muuttui samalla näissä asioissaensimmäiseksi ja ainoaksi muutoksenhakuasteeksi Valiokunnan mielestä käsillä olevanuudistuksen tavoitteet oikeusturvan ja oikeudenhoidon parantamisestaovat tärkeitä hallintolainkäytössä laajemminkin Uudistustarpeiden arvioimisessa on kuitenkin kysymys käytännön toiminnankannalta suurista ja periaatteellisesti merkittävistä asioista,jotka edellyttävät huolellista harkintaa  Sitenniitä on luontevaa arvioida esimerkiksi vireillä olevienhallintolainkäytön kehittämistä koskevienvalmisteluhankkeiden yhteydessä Valiokunta kiinnittää huomiota siihen, että ehdotetunlainsäädännön on tarkoitus tullavoimaan vasta vuoden 2013 alusta  Voimaantuloajankohtaa voidaankuitenkin pitää perusteltuna ottaen huomioon tarvittavaanalemmanasteiseen sääntelyyn, tietojärjestelmänkehittämiseen, oikeudenkäyntiavustajalautakunnanperustamiseen ja muihin täytäntöönpanotoimiinvaadittava aika  Valiokunta tähdentää,että uudistuksen täytäntöönpanemiseksitulee huolehtia myös asianmukaisista voimavaroista  Pykälän  mukaan valvontalautakuntakäsittelee ja ratkaisee luvan saaneita oikeudenkäyntiavustajiakoskevat lain 14 §:ssä tarkoitetut valvonta-asiat Näitä ovat heihin kohdistuvat kirjallisetkantelut sekä oikeuskanslerin ja tuomioistuimen valvontalautakunnalle heistä tekemätilmoitukset  Edellä yleisperusteluissa todetuistasyistä valiokunta ehdottaa pykälän 1momentin loppuun lisättäväksi säännöksen,jonka mukaan luvan saanutta oikeudenkäyntiavustajaa koskevaavalvonta-asiaa käsiteltäessä valvontalautakunnanjaostossa puhetta johtaa asianajajakuntaan kuulumaton jäsen   Eduskunta on 1 2 2011 hyväksynyt lain riita-asioidensovittelusta ja sovinnon vahvistamisesta yleisissä tuomioistuimissa(HE 284/2010 vp - LaVM 32/2010vp)  Lain 15 §:n 2 momenttiin sisältyy lakiviittausoikeudenkäymiskaaren 15 luvun 2 §:ään Luvan saaneita oikeudenkäyntiavustajia koskevanlainsäädäntöuudistuksen yhteydessä oikeudenkäymiskaarenkyseinen pykälä suurelta osin muutetaan  Viittaustaoikeudenkäymiskaareen on tarpeen muuttaa tämänhuomioonottamiseksi   Nyt ehdotettu lainmuutos on tarkoitettu tulemaan voimaan samanaikaisestiluvan saaneista oikeudenkäyntiavustajista annettavanlain kanssa   Pykälän  sisältyylakiviittaus oikeudenkäymiskaaren 15 luvun 2 §:ään Luvan saaneita oikeudenkäyntiavustajia koskevanlainsäädäntöuudistuksen yhteydessä oikeudenkäymiskaaren kyseinenpykälä suurelta osin muutetaan  Tämän vuoksilakiviittausta on tarpeen tarkistaa   Nyt ehdotettu lainmuutos on tarkoitettu tulemaan voimaan samanaikaisestiluvan saaneista oikeudenkäyntiavustajista annettavanlain kanssa   Pykälän  sisältyylakiviittaus oikeudenkäymiskaaren 15 luvun 2 §:ään Luvan saaneita oikeudenkäyntiavustajia koskevanlainsäädäntöuudistuksen yhteydessä oikeudenkäymiskaaren kyseinenpykälä suurelta osin muutetaan  Tämän vuoksilakiviittausta on tarpeen tarkistaa   Nyt ehdotettu lainmuutos on tarkoitettu tulemaan voimaan samanaikaisestiluvan saaneista oikeudenkäyntiavustajista annettavanlain kanssa   Pykälän  sisältyylakiviittaus oikeudenkäymiskaaren 15 luvun 2 §:ään Luvan saaneita oikeudenkäyntiavustajia koskevan lainsäädäntöuudistuksenyhteydessä oikeudenkäymiskaaren kyseinen pykälä suureltaosin muutetaan  Tämän vuoksi lakiviittausta ontarpeen tarkistaa   Pykälän  sisältyylakiviittaus oikeudenkäymiskaaren 15 luvun 2 §:ään Luvan saaneita oikeudenkäyntiavustajia koskevanlainsäädäntöuudistuksen yhteydessä oikeudenkäymiskaaren kyseinenpykälä suurelta osin muutetaan  Tämän vuoksilakiviittausta on tarpeen tarkistaa   Nyt ehdotettu lainmuutos on tarkoitettu tulemaan voimaan samanaikaisestiluvan saaneista oikeudenkäyntiavustajista annettavanlain kanssa Edellä esitetyn perusteella lakivaliokuntaehdottaa,Eduskunnan päätöksen mukaisestisäädetään:Luvan saanutta oikeudenkäyntiavustajaa koskevan 14 §:ssä tarkoitetunvalvonta-asian käsittelee ja ratkaisee valvontalautakunta Valvontalautakunnasta ja sen jäsenistä on voimassa,mitä asianajajista annetun lain 6 a, 7 a ja 7 b §:ssä, 7 j §:n1 momentissa ja 7 k §:ssä säädetään (2 mom  kuten HE)Osapuolen oikeudesta käyttää tuomioistuinsovittelussaavustajaa ja asiamiestä sekä avustajanja asiamiehen kelpoisuuteen sovelletaan, mitä laissa säädetäänoikeudenkäyntiavustajasta ja -asiamiehestä riita-asiassa Sovittelun edistämiseksi sovittelija voi osapuolen esittämästä perustellustasyystä kuitenkin hyväksyä tämän avustajaksitai asiamieheksi muunkin kuin oikeudenkäymiskaaren15 luvun  tarkoitetun  henkilön,joka ei ole konkurssissa ja jonka toimintakelpoisuutta ei ole rajoitettu   Vangin asianajajalleen tai muulle oikeudenkäymiskaaren15 luvun 2 §:n 1 tai  tarkoitetulleoikeudenkäyntiasiamiehelle tai -avustajalle osoittamaakirjettä tai muuta postilähetystä eisaa tarkastaa eikä lukea   Tutkintavangin asianajajalleen tai muulle oikeudenkäymiskaaren15 luvun 2 §:n 1 tai  tarkoitetulleoikeudenkäyntiasiamiehelle tai -avustajalle osoittamaakirjettä tai muuta postilähetystä eisaa tarkastaa eikä lukea   Vapautensa menettäneen siirtämisestä on viipymättä hänensaavuttuaan toiseen säilytystilaan ilmoitettavavapautensa menettäneen osoituksen mukaan hänenlähiomaiselleen tai muulle läheiselleen sekä vapautensamenettäneen asianajajalle tai muulle oikeudenkäymiskaaren15 luvun 2 §:n 1 tai  tarkoitetulleoikeudenkäyntiasiamiehelle tai -avustajalle Jos ilmoittamisesta on erityistä haittaa rikoksen selvittämiselle,ilmoittamista pidätetyn siirtämisestä voidaanlykätä enintään siihen saakka,kun tuomioistuin ottaa pidätettyä koskevanvangitsemisvaatimuksen käsiteltäväksi Vapautensa menettäneen asianajajalleen tai muulle oikeudenkäymiskaaren15 luvun 2 §:n 1 tai  tarkoitetulleoikeudenkäyntiasiamiehelle tai -avustajalle osoittamaakirjettä tai muuta postilähetystä eisaa tarkastaa eikä lukea  Helsingissä 18 päivänä helmikuuta2011Asian ratkaisevaan käsittelyyn valiokunnassaovat ottaneet osaa pj   Janina  Andersson  /vihr  jäs   Esko  Ahonen  /kesk  Kalle  Jokinen  /kok  Oiva  Kaltiokumpu  /kesk  Ilkka  Kantola  /sd  Sampsa  Kataja  /kok  Krista  Kiuru  /sd  Jari  Larikka  /kok  Outi  Mäkelä  /kok  Pirkko  Ruohonen-Lerner  /ps  Tero  Rönni  /sd  Mauri  Salo  /kesk  Kari  Uotila  /vas  Mirja  Vehkaperä  /kesk  Lasse  Virén  /kok Valiokunnan sihteerinä on toiminut valiokuntaneuvos  Minna-Liisa  Rinne Hallituksen esityksen mukainen Suomen Asianajajaliiton valvontayksikölleja valvontalautakunnalle esitetty valvonta- ja kurinpitotoimivaltaluvan saaneiden oikeudenkäyntiavustajien valvojana ei vastaapuolueettoman ja riippumattoman viranomaisvalvonnan vaatimuksia Luvan saaneet oikeudenkäyntiavustajat tulisivat olemaansuurimmaksi osaksi itsenäisinä yrittäjinä toimivienlakiasiaintoimistojen lakimiehiä ja tällöinSuomen Asianajajaliiton jäseninä olevien asianajajienkilpailijoita   Ei ole asianmukaista eikä puolueettomanja riippumattoman valvonnan vaatimuksia täyttävää,että kilpailevan toimistoryhmän etujärjestönelin valvoisi luvan saaneita oikeudenkäyntiavustajia  SuomenAsianajajaliiton valvontayksikkö ja valvontalautakuntaeivät ole luvan saaneisiin oikeudenkäyntiavustajiinnähden puolueeton ja riippumaton valvontaviranomainen Perustuslain 21 §:n 1 momentti edellyttää lainkäyttöelimienolevan riippumattomia   Luvan saaneisiin oikeudenkäyntiavustajiinnähden valvontaelin on lainkäyttöelin,jolta on edellytettävä riippumattomuutta  Perustuslain riippumattomuuden vaatimus ei toteudu, jos SuomenAsianajajaliiton valvontayksikkö ja valvontalautakuntaovat luvan saaneiden oikeudenkäyntiavustajien valvojina Edellä todetuista syistä Suomen Asianajajaliitonvalvontayksikköä ja valvontalautakuntaa ei pidä määrätä luvansaaneiden oikeudenkäyntiavustajien valvontaelimeksi Valiokunnan ehdottamalla muutoksella hallituksen esitykseenvalvontalautakunnan puheenjohtajasta käsiteltäessä luvansaanutta oikeudenkäyntiavustajaa koskevaa kanteluaei ole merkitystä valvontayksikön ja valvontalautakunnanriippumattomuuden kannalta, koska valvontayksikkö ja valvontalautakuntaolisivat Suomen Asianajajaliiton elimiä ja lautakunnan enemmistö muodostuisiasianajajista Suomen Asianajajaliiton puolueellisuutta korostaa järjestönkielteinen suhtautuminen lakiasiaintoimistojen asianajotoimintaanja yleensäkin vapaaseen kilpailuun asianajotoiminnassa Hallituksen esityksen mukaisessa valvontamenettelyssä SuomenAsianajajaliiton valvontalautakunta muuttuisi syyttäjäänverrattavaan asemaan, koska valiokunnan esityksen mukaisesti valvontalautakuntatekisi esityksen kurinpitosakosta tai luvan peruuttamisesta  Valvontalautakunnanroolin muuttuminen menettelyllisesti on ristiriidassa yleisten oikeusperiaatteidenkanssa Valiokunnan mietinnön mukaista esitystä ehdotetaanedellä mainituista syistä muutettavaksi siten,että luvan saaneiden oikeudenkäyntiavustajienvalvontaviranomaisena olisi kaikissa tapauksissa yksinomaanoikeudenkäyntiavustajalautakunta Oikeudenkäyntiavustajalautakunta on puolueetonja riippumaton valvontaviranomainen, jolloin luvan saaneiden oikeudenkäyntiavustajienvalvonta muodostuisi asianmukaiseksi sekä luvan saaneidenoikeudenkäyntiavustajien luottamusta nauttivaksi  Valvonnan keskittäminen yksinomaan oikeudenkäyntiavustajalautakunnalleselkiyttäisi myös menettelyä   Valvonnan erillisyysSuomen Asianajajaliitosta on tarkoituksenmukaista, koska luvan saaneidenoikeudenkäyntiavustajien kohdalla valvonta koskee lainsäännösten noudattamista ja Suomen Asianajajaliitonjäsenvalvonta koskee järjestön omien määräystenja ohjeiden noudattamista  Valvonnan perusteena ei ole tätensaman normiston valvonta Valtiontaloudellisesti ehdotuksesta ei aiheutuisi lisäkustannuksia,koska valvontamaksut menisivät Suomen Asianajajaliitonsijasta oikeudenkäyntiavustajalautakunnalle  Valvontamaksujentuotto riittää lautakunnalle valvonnasta aiheutuviinmenoihin Edellä olevan perusteella ehdotan,Eduskunnan päätöksen mukaisestisäädetään:Tässä laissa tarkoitetun luvan myöntämistäsekä luvanperuuttamista ja seuraamusmaksun määräämistä vartenon riippumaton oikeudenkäyntiavustajalautakunta Luvan saanut oikeudenkäyntiavustaja on 8 §:ssä tarkoitetuissatehtävissään oikeuskanslerin  jaoikeudenkäyntiavustajalautakunnan valvonnan alainen senmukaan kuin tässä laissa säädetään  peruuttamisesta jaseuraamusmaksun määräämisestä päättäminenkuuluvat oikeudenkäyntiavustajalautakunnalle  (1-6 mom  kuten LaVM)Jos luvan saaneen oikeudenkäyntiavustajan lupa on valvonta-asiantultua vireille peruutettu,  oikeudenkäyntiavustajalautakunta  jatkaaasian käsittelyä ja lausua siitä, onkoluvan saanut oikeudenkäyntiavustaja hänen tässä laissatarkoitetun lupansa ollessa voimassa menetellyt moitittavasti jaminkä seuraamuksen hän olisi siitä ansainnut (8 mom  kuten LaVM)Valvonta-asia tulee vireille, kun luvan saaneeseen oikeudenkäyntiavustajaankohdistuva kirjallinen kantelu, oikeuskanslerin 10 §:nnojalla tekemä ilmoitus tai tuomioistuimen oikeudenkäymiskaaren15 luvun 10 a §:n nojalla tekemä ilmoitussaapuu  Jos kantelu on niin puutteellinen, ettei asiaa voida sen perusteellaottaa ratkaistavaksi, kantelijaa on määräajassakehotettava korjaamaan puute  Kantelijalle on samalla ilmoitettava, millä tavoinkantelu on puutteellinen, ja että  voijättää kantelun tutkittavaksi ottamatta,jos kantelija ei noudata saamaansa täydennyskehotusta   eiota tutkittavaksi aikaisemmin ratkaistua asiaa koskevaa kantelua,ellei kantelussa ole esitetty asiaan vaikuttavaa uutta selvitystä (3 mom  kuten LaVM)(1 mom )Menettely valvonta-asiaa  käsiteltäessä onkirjallinen    voidaan kuitenkintehdä vain, jos asiassa  onjärjestetty suullinen käsittely    voimuutoinkin järjestää suullisen käsittelyn  Luvan saanut oikeudenkäyntiavustaja, jota valvonta-asiakoskee, ja kantelija on kutsuttava suulliseen käsittelyyn  on varattavaluvan saaneelle oikeudenkäyntiavustajalle, jota valvonta-asiakoskee, tilaisuus tulla kuulluksi ennen asian ratkaisua   Oikeudenkäyntiavustajantulee antaa häneltä pyydetyt tiedot ja selvityksetavoimesti ja totuudenmukaisesti    onvarattava kantelijalle tilaisuus lausua oikeudenkäyntiavustajanantaman vastauksen johdosta    onmuutoinkin huolehdittava siitä, että asia tuleeriittävästi selvitetyksi  on pidettävä valvonta-asioistajulkista päiväkirjaa Tieto luvan peruuttamisesta poistetaanjulkisesta päiväkirjasta kymmenen vuoden kuluttuaoikeudenkäyntiavustajalautakunnan päätöksenantamisesta  Siltä osin kuin laissa ei toisin säädetä,valvonta-asian käsittelyyn  oikeudenkäyntiavustajalautakunnassasovelletaan, mitä hallintolaissa (434/2003),kielilaissa (423/2003) ja saamen kielilaissa(1086/2003) säädetään  asiakirjojenja toiminnan julkisuuteen valvonta-asiassa sovelletaan, mitä viranomaistentoiminnan julkisuudesta annetussa laissa (621/1999)säädetään, jolleiluvan saaneen oikeudenkäyntiavustajan salassapitovelvollisuudestamuuta johdu  Asiakirja ei kuitenkaan tule julkiseksi, ennen kuin  oikeudenkäyntiavustajalautakunnanpäätös on annettu tai kun se on asiaan osallisensaatavissa (1 mom  kuten LaVM)Luvan saaneen oikeudenkäyntiavustajan on lisäksisuoritettava valvontamaksu  Valvontamaksu on suoritettava siltä vuodelta,jona oikeudenkäyntiavustajalle myönnetääntässä laissa tarkoitettu lupa, sekä lisäksijokaiselta kalenterivuodelta, jonka alkaessa hänenlupansa on voimassa  Valvontamaksun suuruus on 350 euroa ja senperii oikeudenkäyntiavustajalautakunta  Oikeusministeriö tarkistaavuosittain valvontamaksun suuruuden elinkustannusindeksin nousuavastaavasti  Tarkistus tehdään kahden täydeneuron tarkkuudella (1 mom  kuten LaVM)Luvan saaneella oikeudenkäyntiavustajalla, jota valvonta-asiakoskee, ja oikeuskanslerilla on oikeus valittaa 14 §:ssä tarkoitetussavalvonta-asiassa annetusta  oikeudenkäyntiavustajalautakunnanpäätöksestä Helsingin hovioikeuteen  Määräaika valituksen tekemiselleon 30 päivää   Valitusaika alkaa siitä päivästä,jona päätös on annettu tiedoksi   Helsinginhovioikeudelle osoitettu valituskirjelmä on toimitettava määräajassa ,jonka päätökseen valitus kohdistuu (4 mom  kuten LaVM) valvontalautakunnanon toimitettava oikeudenkäyntiavustajalautakunnalle ilmoituspäätöksestä, jolla henkilö kurinpidollisenaseuraamuksena on erotettu asianajajayhdistyksestä taion poistettu asianajajista annetun lain 5 b §:n 1momentissa tarkoitetusta EU-luettelosta, sekä samalla ilmoitettava,onko päätös saanut lainvoiman (2 ja 3 mom  kuten LaVM) on lähetettävä oikeuskanslerillejäljennös 14 §:ssä tarkoitetussavalvonta-asiassa antamastaan päätöksestä Lisäksi oikeudenkäyntiavustajalautakunnan on lähetettävä oikeuskanslerillejäljennös 20 §:ssä tarkoitetustaluvan peruuttamista koskevasta päätöksestään (5 mom  )(5 mom  kuten LaVM:n 6 mom )Eduskunnan päätöksen mukaisesti asianajajista annetun lain (496/1958)3 §:n 1 ja 2 momentti, 7 ja 7 a §, 7 b §:n1 ja 2 momentti, 7 c §:n 1 momentti, 7 e §:n 1momentti, 7 h §:n 1 momentti, 7 i ja 7 j §, 9 §:n1 momentti sekä 10 §:n 1 ja 3 momentti,sellaisina kuin ne ovat, 3 §:n 1 momenttilaissa 1095/2007, 3 §:n2 momentti laissa 31/1993 sekä 7 ja7 a §, 7 b §:n 1 ja 2 momentti, 7 c §:n1 momentti, 7 e §:n 1 momentti, 7 h §:n 1 momentti,7 i ja 7 j §, 9 §:n 1 momenttisekä 10 §:n 1 ja 3 momentti laissa 697/2004,sekä 3 §:ään,sellaisena kuin se on laeissa 31/1993, 1249/1999 ja 1095/2007 uusi5 ja 6 momentti, lakiin siitä lailla 697/2004 kumotun6 a §:n tilalle uusi 6 a §, lakiin uusi 7 k §,8 §:ään, sellaisena kuin seon laissa 1249/1999, uusi 2 momenttija lakiin uusi 13 b § seuraavasti:Asianajajayhdistyksen yhteydessä toimivat riippumattomatvalvontalautakunta ja valvontayksikkö, joille kuuluvattässä laissa säädetyt asianajajientoiminnan valvontaan liittyvät tehtävät Sen mukaan kuin valtion oikeusaputoimistoista annetussa laissa (258/2002)  säädetään,valvontalautakunnalle ja valvontayksikölle kuuluvat myösjulkisten oikeusavustajien  toiminnan valvontaanliittyvät tehtävät (2-5 mom  kuten LaVM)Valvontalautakuntaan kuuluvat puheenjohtaja ja yksitoistamuuta jäsentä sekä heidän kunkinhenkilökohtaiset varajäsenet   Puheenjohtajanja hänen varajäsenensä sekä kuudenmuun jäsenen ja heidän varajäsentensä tuleeolla asianajajia    jäsenen ja heidänvarajäsentensä tulee olla asianajajakuntaan kuulumattomia oikeustieteenmuun ylemmän korkeakoulututkinnon kuin kansainvälisenja vertailevan oikeustieteen maisterin tutkinnon suorittaneita henkiöitä,jotka ovat perehtyneitä asianajotoimintaan sekä lisäksituomarin tehtäviin taikka oikeustieteen yliopistolliseenkoulutukseen ja tutkimukseen  Valvontalautakunnanjäsenten ja varajäsenten toimikausi on kolme vuotta Asianajajayhdistyksen valtuuskunta valitsee valvontalautakunnanpuheenjohtajan ja hänen varajäsenensä sekä asianajajakuntaankuuluvat jäsenet ja heidän varajäsenensä  Valtioneuvosto nimittää asianajajakuntaan kuulumattomatvalvontalautakunnan jäsenet ja heidän varajäsenensä oikeusministeriönesityksestä   Ennen esityksen tekemistä oikeusministeriönon pyydettävä ehdokkaiden kelpoisuudesta tehtäväänasianajajayhdistyksen lausunto  Lausunto on pyydettävä kaksikertaa niin monesta ehdokkaasta kuin on nimitettäviä Valvontalautakunta valitsee keskuudestaan kolme varapuheenjohtajaa (3 mom  kuten LaVM)Valvontalautakunta voi toimia neljänä jaostona,joihin kuhunkin kuuluu kolme jäsentä  Jaostojenkokoonpanon määrää valvontalautakuntasiten, että kuhunkin jaostoon kuuluu jäseninä enintäänkaksi asianajajaa ja enintään kaksi asianajajakuntaankuulumatonta jäsentä   Jaostojen puheenjohtajinatoimivat valvontalautakunnan puheenjohtaja ja kolme varapuheenjohtajaa  Palkkioriita-asiaa käsiteltäessä jaostossa johtaapuhetta kuitenkin 7 a §:n 1 momentissa tarkoitettujäsen, joka ei ole asianajaja Valvontalautakunnan täyistunto on päätösvaltainen,kun puheenjohtaja tai varapuheenjohtaja ja vähintään muutajäsentä on saapuvilla  Valvontalautakunnan jaostoon päätösvaltainen, kun kaikki sen jäsenetovat saapuvilla (1 ja 2 mom  kuten LaVM)Tämän lain voimaan tullessa valvontalautakunnanjäseninä olevien toimikausi jatkuu sen määräajanloppuun, joksi heidät on valittu tai nimitetty  (4-6 mom  kuten LaVM)Eduskunnan päätöksen mukaisesti oikeudenkäymiskaaren 15 luvun 2 §,4 §:n 1 momentti ja 10 a §:n1 momentti sekä 31 luvun 13 § ja18 §:n 3 momentti, sellaisina kuin ne ovat, 15 luvun 2 § laeissa 764/2001, 259/2002 ja 578/2009,4 §:n 1 momentti laissa 259/2002 ja10 a §:n 1 momentti laissa 497/1958 sekä 31luvun 13 § laissa 109/1960 ja 18 §:n3 momentti laissa 666/2005, sekä lain 15 luvun1 §:ään, sellaisena kuin se on laissa21/1972, uusi 4 momentti ja 31 luvun 3 §:ään,sellaisena kuin se on laissa 109/1960, uusi 2 momenttiseuraavasti:Jos oikeudenkäyntiasiamies tai -avustaja osoittautuuepärehelliseksi, ymmärtämättömäksitai taitamattomaksi taikka jos hänet havaitaan toimeensamuutoin sopimattomaksi, saa tuomioistuin kieltää häntä esiintymästä kyseisessä asiassa Tuomioistuin voi myös, jos siihen on syytä, kieltää häneltä oikeudentoimia siinä tuomioistuimessa oikeudenkäyntiasiamiehenä tai -avustajanaenintään kolmen vuoden ajaksi  Kun päätöskoskee asianajajaa  julkista oikeusavustajaa ,tuomioistuimen on ilmoitettava päätöksestä asianajajistaannetun lain (496/1958) 6 a §:n1 momentissa tarkoitetulle valvontalautakunnalle   Jos asianajaja julkinenoikeusavustaja  menettelee muutoin velvollisuuksiensavastaisesti, tuomioistuin voi ilmoittaa menettelyn valvontalautakunnankäsiteltäväksi   Helsingissä 18päivänä helmikuuta 2011 Pirkko  Ruohonen-Lerner  /ps  < Paluu  |  Sivun alkuun        Käyttöehdot |         PalauteEduskunnan vaihde: (09) 4321, 00102 Eduskunta | Yhteystiedot | Aakkosellinen hakemisto |Ohjeet", new Locale("fi"), Arrays.asList(new String[] { "V N Nom Sg", "A Pos Nom Pl", "Num Nom Pl", " N Prop Nom Sg", "N Nom Pl" }),true, true, true,0);
		/*		System.out.println(las.analyze("Helsingissä vastaukset varusteet komentosillat tietokannat tulosteet kriisipuhelimet kuin hyllyt", new Locale("fi")));
				System.out.println(las.analyze("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", new Locale("fi")));*/
		System.exit(0);
	}
}
