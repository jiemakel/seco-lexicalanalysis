package fi.seco.lexical.combined;

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

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import marmot.core.Model;
import marmot.core.SimpleTagger;
import marmot.core.Tagger;
import marmot.morph.MorphWeightVector;
import marmot.morph.Sentence;
import marmot.morph.Word;
import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fi.seco.hfst.Transducer;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.WordToResults;
import fi.seco.lexical.hfst.HFSTLexicalAnalysisService.Result.WordPart;

public class CombinedLexicalAnalysisService extends HFSTLexicalAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(CombinedLexicalAnalysisService.class);
	
	private Map<Locale,SentenceDetector> sdMap = new HashMap<Locale,SentenceDetector>();
	private Map<Locale,Tokenizer> tMap = new HashMap<Locale,Tokenizer>();
	
	private Set<Locale> supportedLocales = new HashSet<Locale>();
	
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
		SentenceDetector sd = sdMap.get(lang);
		if (sd!=null) return sd;
		InputStream modelIn = CombinedLexicalAnalysisService.class.getResourceAsStream(lang+"-sent.bin");
		try {
		  sd = new SentenceDetectorME(new SentenceModel(modelIn));
		  sdMap.put(lang,sd);
		  return sd;
		}
		catch (IOException e) {
		  throw new IOError(e);
		}
		finally {
		  if (modelIn != null) {
		    try {
		      modelIn.close();
		    }
		    catch (IOException e) {
		    }
		  }
		}
	}
	
	private Tokenizer getTokenizer(Locale lang) {
		Tokenizer t = tMap.get(lang);
		if (t!=null) return t;
		InputStream modelIn = CombinedLexicalAnalysisService.class.getResourceAsStream(lang+"-token.bin");
		try {
		  t = new TokenizerME(new TokenizerModel(modelIn));
		  tMap.put(lang,t);
		  return t;
		}
		catch (IOException e) {
		  throw new IOError(e);
		}
		finally {
		  if (modelIn != null) {
		    try {
		      modelIn.close();
		    }
		    catch (IOException e) {
		    }
		  }
		}	}
	
	private static final Locale fi = new Locale("fi");
	
	private static final Tagger fitag;
	static {
		Tagger tmp=null;
		try {
			tmp = ((Tagger) new ObjectInputStream(new GZIPInputStream(CombinedLexicalAnalysisService.class.getResourceAsStream("fin_model.marmot"))).readObject());
		} catch (ClassNotFoundException e) {
		} catch (IOException e) {
		}
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

          options.featureCreation=dis.readInt();

          for (int t=0;t<THREADS;t++) pipe.extractor[t]=new Extractor(l2i, stack,options.featureCreation);

          Extractor.initFeatures();
          Extractor.initStat(options.featureCreation);


          for (int t=0;t<THREADS;t++)  pipe.extractor[t].init();

          Edges.read(dis);

          options.decodeProjective = dis.readBoolean();

          Extractor.maxForm = dis.readInt();

          boolean foundInfo =false;
          String info =null;
          int icnt = dis.readInt();
          for(int i=0;i<icnt;i++) {
                  info = dis.readUTF();
//                System.out.println(info);
          }


          dis.close();

          Decoder.NON_PROJECTIVITY_THRESHOLD =(float)options.decodeTH;

          Extractor.initStat(options.featureCreation);
		} catch (IOException e) {
			throw new IOError(e);
		}

	}
	
	private static final Map<String,String> posMap = new HashMap<String,String>();
	
	private static final Map<String,Set<String>> rposMap = new HashMap<String,Set<String>>();
	
	static {
		posMap.put("NOUN","POS_N");
		posMap.put("VERB","POS_V");
		posMap.put("ADVERB","POS_Adv");
		posMap.put("ADPOSITION","POS_Adp");
		posMap.put("PUNCTUATION","POS_Punct");
		posMap.put("PRONOUN","POS_Pron");
		posMap.put("ADJECTIVE","POS_Adj");
		posMap.put("PARTICLE","POS_Adv");
		posMap.put("NUMERAL","POS_Num");
		rposMap.put("N",new HashSet<String>(Arrays.asList(new String[] {"NOUN"})));
		rposMap.put("V",new HashSet<String>(Arrays.asList(new String[] {"VERB"})));
		rposMap.put("Adv",new HashSet<String>(Arrays.asList(new String[] {"ADVERB","PARTICLE"})));
		rposMap.put("C",new HashSet<String>(Arrays.asList(new String[] {"PARTICLE"})));
		rposMap.put("Adp",new HashSet<String>(Arrays.asList(new String[] {"ADPOSITION"})));
		rposMap.put("A",new HashSet<String>(Arrays.asList(new String[] {"ADJECTIVE"})));
		rposMap.put("Punct",new HashSet<String>(Arrays.asList(new String[] {"PUNCTUATION"})));
		rposMap.put("Pron",new HashSet<String>(Arrays.asList(new String[] {"PRONOUN"})));
		rposMap.put("Num",new HashSet<String>(Arrays.asList(new String[] {"NUMERAL"})));
	}
	
	private static final Map<String,String> subcatMap = new HashMap<String,String>();
	
	static {
		subcatMap.put("INTERJECTION","POS_Interj");
		subcatMap.put("CONJUNCTION","POS_C");
		subcatMap.put("ABBREVIATION","POS_N");
		subcatMap.put("QUALIFIER","POS_Adv");
	}

	private static final Map<String,String> conjMap = new HashMap<String,String>();
	
	static {
		conjMap.put("ADVERBIAL","POS_Adv");
		conjMap.put("COORD","POS_C");
	}

	private static String map(String pos, String subcat, String conj) {
		String ret;
		if ("PARTICLE".equals(pos) && subcat!=null) {
			if (subcat=="CONJUNCTION" && conj!=null) {
				ret = conjMap.get(conj);
			} else ret = subcatMap.get(subcat);
		} else {
			ret = posMap.get(pos);
		}
		if (ret==null) {
//			System.err.println("Unknown POS: "+pos+","+subcat+","+conj);
			ret="POS_"+pos; 
		}
		return ret;
	}
	
	private static Set<String> rmap(String pos) {
		Set<String> ret = rposMap.get(pos);
		if (ret!=null) return ret;
//		System.out.println("Unknown POS: "+pos);
		return Collections.singleton(pos);
	}
	
	@Override
	public String baseform(String string, Locale lang) {
		try {
			List<WordToResults> crc = analyze(string, lang,false);
			StringBuilder ret = new StringBuilder();
			for (WordToResults cr : crc) {
				ret.append(getBestLemma(cr, lang));
				ret.append(' ');
			}
			return ret.toString().trim();
		} catch (ArrayIndexOutOfBoundsException e) {
			return string;
		}
	}
	
	protected String getBestLemma(WordToResults cr, Locale lang) {
		float cw = Float.MAX_VALUE;
		StringBuilder cur = new StringBuilder();
		for (Result r : cr.getAnalysis())
			if (r.getWeight() < cw) {
				cur.setLength(0);
				for (WordPart wp : r.getParts())
					cur.append(wp.getLemma());
				cw = r.getWeight();
			}
		cw = Float.MAX_VALUE;
		for (Result r : cr.getAnalysis())
			if (r.getGlobalTags().containsKey("POS_MATCH") && r.getWeight() < cw) {
				cur.setLength(0);
				for (WordPart wp : r.getParts())
					cur.append(wp.getLemma());
				cw = r.getWeight();
			}
		return cur.toString();
	}
	
	private List<WordToResults> analyze(String str, Locale lang, boolean deprel) {
		if (!supportedLocales.contains(lang)) return super.analyze(str, lang);
		Tokenizer t = getTokenizer(lang);
		Transducer tc = getTransducer(lang, "analysis", at);
		int i=0;
		List<WordToResults> ret = new ArrayList<WordToResults>();
		for (String sentence : getSentenceDetector(lang).sentDetect(str)) {
			for (String word : t.tokenize(sentence)) {
				List<Result> r = toResult(tc.analyze(word));
				if (r.isEmpty()) r = toResult(tc.analyze(word.toLowerCase()));
				if (r.isEmpty()) r.add(new Result().addPart(new WordPart(word)));
				ret.add(new WordToResults(word, r));
			}
			if (fi.equals(lang)) {
				List<Word> tokens = new ArrayList<Word>(ret.size()-i);
				int j = i;
				while(i<ret.size()) {
					WordToResults wtr = ret.get(i++);
					Set<String> tf = new HashSet<String>();
					for (Result r : wtr.getAnalysis()) {
						List<String> aPOS = r.getParts().isEmpty() ? null : r.getParts().get(r.getParts().size()-1).getTags().get("POS");
						List<String> aSUBCAT = r.getParts().isEmpty() ? null : r.getParts().get(r.getParts().size()-1).getTags().get("SUBCAT");
						List<String> aCONJ = r.getParts().isEmpty() ? null : r.getParts().get(r.getParts().size()-1).getTags().get("CONJ");
						if (aPOS==null) tf.add("_");
						else tf.add(map(aPOS.get(0),aSUBCAT==null ? null : aSUBCAT.get(0),aCONJ == null ? null : aCONJ.get(0)));
					}
					String[] termFeatures = tf.toArray(new String[tf.size()]);
					Arrays.sort(termFeatures);
					tokens.add(new Word(wtr.getWord(),null,null,termFeatures,null,null));
				}
				List<List<String>> tags;
				synchronized (fitag) {
					tags = fitag.tag(new Sentence(tokens));
				}
				i = j;
				for (int k = 0;k<tags.size();k++)
					for (Result r : ret.get(i++).getAnalysis()) if (!r.getParts().isEmpty()) {
						List<String> aPOS = r.getParts().get(r.getParts().size()-1).getTags().get("POS");
						if (aPOS!=null) {
							List<String> ctags = tags.get(k);
							Set<String> gPOS = rmap(ctags.get(0));
							for (String pos : aPOS) if (gPOS.contains(pos))
								r.addGlobalTag("POS_MATCH", "TRUE");
						}
					}
				if (deprel) {
					i = j;
					SentenceData09 sd = new SentenceData09();
					String[] forms = new String[tokens.size()+1];
					forms[0]=IOGenerals.ROOT;
					String[] lemmas = new String[forms.length];
					lemmas[0]=IOGenerals.ROOT_LEMMA;
					String[] poss = new String[forms.length];
					poss[0]=IOGenerals.ROOT_POS;
					String[] feats = new String[forms.length];
					feats[0]=IOGenerals.EMPTY_FEAT;
					for (int k = 1;k<forms.length;k++) {
						WordToResults wtr = ret.get(i++);
						List<String> ctags = tags.get(k-1);
						forms[k]=wtr.getWord();
						lemmas[k]=wtr.getWord();
						feats[k]="_";
						poss[k]="_";
						for (Result r : wtr.getAnalysis()) if (!r.getParts().isEmpty() && r.getGlobalTags().containsKey("POS_MATCH")) {
							StringBuilder sb = new StringBuilder();
							for (WordPart p : r.getParts()) {
								sb.append(p.getLemma());
								sb.append('|');
							}
							sb.setLength(sb.length()-1);
							lemmas[k]=sb.toString();
							poss[k]=ctags.get(0);
							sb.setLength(0);
							for (int l=1;l<ctags.size();l++) {
								sb.append(ctags.get(l));
								sb.append('|');
							}
							sb.setLength(sb.length()-1);
							feats[k]=sb.toString();
						}
					}
					sd.init(forms);
					sd.setLemmas(lemmas);
					sd.setPPos(poss);
					sd.setFeats(feats);
					SentenceData09 out;
					synchronized(fiparser) {
						out = fiparser.parse(sd, fiparser.params, false, fiparser.options);
					}
					i = j;
					for (int k=0;k<out.forms.length;k++) {
						WordToResults wtr = ret.get(i++);
						Result mr = null;
						for (Result r : wtr.getAnalysis())
							if (r.getGlobalTags().containsKey("POS_MATCH")) mr=r;
						if (mr!=null) {
							mr.addGlobalTag("HEAD", ""+(j+out.pheads[k]));
							mr.addGlobalTag("DEPREL", out.plabels[k]);
						}
					}
				}
			}
		}
		return ret;		
	}
	
	@Override
	public List<WordToResults> analyze(String str, Locale lang) {
		return analyze(str,lang,true);
	}
	
	public static void main(String[] args) {
		final CombinedLexicalAnalysisService las = new CombinedLexicalAnalysisService();
//		las.analyze("Maanmittauksessa Swardlucken kiintopiste on kuin onkin maastoon pysyvästi merkitty maastonkohta, jonka sijainti tai korkeus on tarkasti määritetty suhteessa muihin kiintopisteisiin tai tiettyyn koordinaatistoon. Merkkinä voi olla esimerkiksi pultti kalliossa tai kadun pinnassa, maston tai majakan huippu, tai valtakunnan rajapyykki. Kiintopisteitä käytetään lähtökohtana, kun määritetään muiden maastonkohtien sijainteja tai korkeuksia. Kiintopiste voi olla tasokiintopiste, jolloin sen paikka tunnetaan tarkasti leveys- ja korkeuspiirien suhteen, korkeuskiintopiste, jolloin sen paikka tunnetaan pystysuunnassa suhteessa keskimääräiseen merenpinnan tasoon tai teoreettiseen geoidiin, tai se voi olla molempia samanaikaisesti. Suomessa valtakunnallista kiintopisterekisteriä pitää maanmittauslaitos. Kunnilla on omia kiintopisteverkkojaan. Tasokiitopisteiden mittauksissa on aiemmin käytetty kolmiomittausta, kun taas nykyään käytetään erityisesti maanmittaustarkoitukseen suunniteltuja, tavallista tarkempia satelliittipaikannusmenetelmiä. Korkeuskiintopisteet on puolestaan määritetty vaaitsemalla. Mittaustavoista johtuen kiintopisteen paikan epävarmuus on suurimmillaan korkeudessa. Vaakatasossa tarkkuus on parhaimmillaan millimetriluokkaa, samalla kun pystysuuntaan harvoin päästään laajemmalla alalla edes alhaisiin senttimetreihin, satelliittejakaan hyödyntäen. Tamman tiineys kestää noin 11 kalenterikuukautta. Tiineyden alussa tammaa voidaan kouluttaa aivan tavalliseen tapaan. Kun tamman vatsa alkaa kasvaa, ja sen paino alkaa nousta, on käytettävä maalaisjärkeä. Tammalta ei saa vaatia liikaa. On tärkeää, että kantava tamma saa olla paljon ulkona ja liikkua mielihalujensa mukaan. Eräät tammat saattavat muuttua hieman ärtyisiksi tiineyden aikana: asiat, jotka ovat olleet niille yhdentekeviä aikaisemmin, ovatkin nyt vaikeampia. Tammaa saattaa esimerkiksi ärsyttää, kun sitä harjataan vatsan alta tai kun satulavyötä kiristetään. Jotkut tammat saattavat muuttua levottomiksi tiineyden edetessä. Kaikki tämä on aivan normaalia, joten syytä huoleen ei ole. Tämä kaikki on selvä merkki siitä, että tamma tietää, mikä sitä odottaa. Eräät tammat ovat tiineyden aikana niin ärtyisiä, että niiden vatsa pitäisi jättää rauhaan. Kun tamma on kantava, täytyy ajatella tamman ja kohdussa olevan varsan terveyttä. Tiineelle tammalle ei kannata antaa tavallista rehua. Kantaville tammoille on tarkoitettu oma erityisrehu, jota kaikki rehuntoimittajat myyvät. Muutama päivä ennen synnytystä jo aiemmin varsoneiden tammojen utareisiin ilmestyy ns. vahatapit. Siitä tietää, että varsominen on lähellä. Selkie on kylä Kontiolahdella. Se sijaitsee kunnan itäosassa Ilomantsintieltä erkanevan Jakokoski-Heinävaara -tien eli Mönnin-Selkien maantien (yhdystie 5100) varrella. Kylän naapurikyliä ovat Heinävaara ja Mönni. Asukkaita kylällä on noin 270.[1] Selillä on kyläkoulu sekä oma evankelisluterilainen hautausmaakappeli Selkien-Mönnin hautausmaalla. Alakoulussa on tällä hetkellä noin 35 oppilasta Seliltä ja Mönnistä. Kylästä on arkipäivisin linja-autoyhteys Kontiolahden ja Joensuun suuntiin. Liikuntatiloja on Palotalon nykyaikaisessa urheiluhallissa. Mustavaaran laskettelukeskus sijoittuu kylän etelälaidalle. Selkie-Lehtoi on nauhamainen kyläkokonaisuus, joka ryhmittyy korkealle vaaralinjalle vanhan Tohmajärven maantien varteen. Tie seuraa paikoitellen 1600-luvun ensimmäistä, rinteille kaskiviljelyn tuoman asutuksen synnyttämää kylätien linjaa. Vaarat nousevat paikoin yli 200 metrin korkeudelle merenpinnasta. Kylältä on toistaiseksi laajoja selkeitä näkymiä ympäristöön: suora näköyhteys jopa Joensuun keskustaan, Pielisjoelle ja Kolille asti. Möykynmäki on seudun tieliikenneympäristöistä paikallisesti koettavin. Kylän nimi \"Selkien kylä\" taipuu muodossa \"Selillä, Seliltä, Selille\"; Lehtoin kylä (varhain oma kylänsä) \"Lehtoilla, Lehtoilta, Lehtoille\". Nykyinen kirjoitusasu on todennäköisesti peräisin Venäjän vallan ajalta, ja tämä on edesauttanut nykyisiäkin poikkeavia kielellisiä variaatioita. Virallisissa yhteyksissä sallitaan muodollisesti \"Selkiellä\" jne. Selkien pohjoisin osa kuuluu valtakunnallisesti merkittäviin rakennettuihin kulttuuriympäristöihin. Samoin Selkien-Lehtoin-Heinävaaran kyläalue on yksi valtioneuvoston periaatepäätöksen mukaisista valtakunnallisesti arvokkaista maisema-alueista (koko maassa 156). Muiden Pohjois-Karjalan vaarakylien tapaan Selkie on osa ympäristöministeriön vuonna 1992, Suomen 75-vuotisjuhlavuonna, julkistamia Suomen kansallismaisemia[3]. Historiallisen, kulttuuri- tai luonnonmaisemansa puolesta merkityksellisiksi kansallismaisemiksi on valittu yhteensä 27 väljää aluekokonaisuutta (maisematyyppiä) ympäri Suomen. Luokittelulla ei ole juridista merkitystä. Selkien kyläalueeseen kuuluvat Selkien ja Lehtoin lisäksi muiden muassa Elovaara, Havukkavaara, Jukajoki ja Mustavaara. Kylän postitoimipaikka oli 81235 Lehtoi vuoden 2012 lopputalveen saakka. ", new Locale("fi"));
		new Thread() {
			@Override
			public void run() {
				System.out.println(las.analyze("sanomalehteä luin Suomessa", new Locale("fi")));
			}
		}.start();
		System.out.println(las.analyze("sanomalehteä luin Suomessa", new Locale("fi")));
/*		System.out.println(las.analyze("Helsingissä vastaukset varusteet komentosillat tietokannat tulosteet kriisipuhelimet kuin hyllyt", new Locale("fi")));
		System.out.println(las.analyze("sanomalehteä luin Suomessa kolmannen valtakunnan punaisella Porvoon asemalla", new Locale("fi")));*/
		System.exit(0);
	}
}
