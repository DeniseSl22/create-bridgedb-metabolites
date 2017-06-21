// export CLASSPATH=`ls -1 *.jar | tr '\n' ':'`

import java.text.SimpleDateFormat;
import java.util.Date;
import groovy.util.slurpersupport.NodeChildren;

import org.bridgedb.IDMapperException;
import org.bridgedb.DataSource;
import org.bridgedb.Xref;
import org.bridgedb.bio.BioDataSource;
import org.bridgedb.rdb.construct.DBConnector;
import org.bridgedb.rdb.construct.DataDerby;
import org.bridgedb.rdb.construct.GdbConstruct;
import org.bridgedb.rdb.construct.GdbConstructImpl3;

commitInterval = 500
genesDone = new java.util.HashSet();
linksDone = new java.util.HashSet();

GdbConstruct database = GdbConstructImpl3.createInstance(
  "hmdb_chebi_wikidata_metabolites", new DataDerby(), DBConnector.PROP_RECREATE
);
database.createGdbTables();
database.preInsert();

blacklist = new HashSet<String>();
blacklist.add("C00350")
blacklist.add("C00157")
blacklist.add("C00422")
blacklist.add("C00165")
blacklist.add("C02530")
blacklist.add("C00416")
blacklist.add("C02737")
blacklist.add("363-24-6")
blacklist.add("104404-17-3")
blacklist.add("CHEBI:17636")
blacklist.add("HMDB00912") // see bug #6

//inchiDS = DataSource.register ("Cin", "InChI").asDataSource()
inchikeyDS = DataSource.register ("Ik", "InChIKey").asDataSource()
chemspiderDS = DataSource.register ("Cs", "Chemspider").asDataSource()
casDS = BioDataSource.CAS
pubchemDS = BioDataSource.PUBCHEM_COMPOUND
chebiDS = BioDataSource.CHEBI
keggDS = BioDataSource.KEGG_COMPOUND
keggDrugDS = DataSource.register ("Kd", "KEGG Drug").asDataSource()
wikidataDS = DataSource.register ("Wd", "Wikidata").asDataSource()
lmDS = DataSource.register ("Lm", "LIPID MAPS").asDataSource()
knapsackDS = DataSource.register ("Cks", "KNApSAcK").asDataSource()
dtxDS = DataSource.register ("Dtx", "EPA CompTox Dashboard").asDataSource()
// drugbankDS = BioDataSource.DRUGBANK

String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
database.setInfo("BUILDDATE", dateStr);
database.setInfo("DATASOURCENAME", "HMDB-CHEBI-WIKIDATA");
database.setInfo("DATASOURCEVERSION", "HMDB3.6-CHEBI152-WIKIDATA20170621" + dateStr);
database.setInfo("DATATYPE", "Metabolite");
database.setInfo("SERIES", "standard_metabolite");

def addXRef(GdbConstruct database, Xref ref, String node, DataSource source, Set genesDone, Set linkesDone) {
   id = node.trim()
   if (id.length() > 0) {
     // println "id($source): $id"
     ref2 = new Xref(id, source);
     if (!genesDone.contains(ref2.toString())) {
       if (database.addGene(ref2) != 0) {
          println "Error (addXRef.addGene): " + database.recentException().getMessage()
          println "                 id($source): $id"
       }
       genesDone.add(ref2.toString())
     }
     if (!linksDone.contains(ref.toString()+ref2.toString())) {
       if (database.addLink(ref, ref2) != 0) {
         println "Error (addXRef.addLink): " + database.recentException().getMessage()
         println "                 id(origin):  " + ref.toString()
         println "                 id($source): $id"
       }
       linksDone.add(ref.toString()+ref2.toString())
     }
   }
}

def addAttribute(GdbConstruct database, Xref ref, String key, String value) {
   id = value.trim()
   // println "attrib($key): $id"
   if (id.length() > 255) {
     println "Warn: attribute does not fit the Derby SQL schema: $id"
   } else if (id.length() > 0) {
     if (database.addAttribute(ref, key, value) != 0) {
       println "Error (addAttrib): " + database.getException().getMessage()
     }
   }
}

def cleanKey(String inchikey) {
   String cleanKey = inchikey.trim()
   if (cleanKey.startsWith("InChIKey=")) cleanKey = cleanKey.substring(9)
   cleanKey
}

// load the HMDB content
counter = 0
def zipFile = new java.util.zip.ZipFile(new File('hmdb_metabolites.zip'))
zipFile.entries().each { entry ->
   if (!entry.isDirectory() && entry.name != "hmdb_metabolites.xml") {
     inputStream = zipFile.getInputStream(entry)
     def rootNode = new XmlSlurper().parse(inputStream)
     error = 0

     String rootid = rootNode.accession.toString()
     Xref ref = new Xref(rootid, BioDataSource.HMDB);
     if (!genesDone.contains(ref.toString())) {
       addError = database.addGene(ref);
       if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
       error += addError
       linkError = database.addLink(ref,ref);
       if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
       error += linkError
       genesDone.add(ref.toString())
     }

     // add the synonyms
     addAttribute(database, ref, "Symbol", rootNode.name.toString());
     rootNode.synonyms.synonym.each { synonym ->
       addAttribute(database, ref, "Synonym", synonym.toString())
     }
     addAttribute(database, ref, "Synonym", rootNode.traditional_iupac.toString());
     addAttribute(database, ref, "Synonym", rootNode.iupac_name.toString());

     // add the SMILES, InChIKey, etc
     addAttribute(database, ref, "InChI", cleanKey(rootNode.inchi.toString()));
     key = cleanKey(rootNode.inchikey.toString().trim());
     if (key.length() == 27) {
       addAttribute(database, ref, "InChIKey", key);
       addXRef(database, ref, key, inchikeyDS, genesDone, linksDone);
     }
     addAttribute(database, ref, "SMILES", rootNode.smiles.toString());
     addAttribute(database, ref, "BrutoFormula", rootNode.chemical_formula.toString());
     addAttribute(database, ref, "Taxonomy Parent", rootNode.direct_parent.toString());
     addAttribute(database, ref, "Monoisotopic Weight", rootNode.monisotopic_moleculate_weight.toString());

     // add external identifiers
     // addXRef(database, ref, rootNode.accession.toString(), BioDataSource.HMDB);
     if (!blacklist.contains(rootid)) {
       addXRef(database, ref, rootNode.cas_registry_number.toString(), casDS, genesDone, linksDone);
       addXRef(database, ref, rootNode.pubchem_compound_id.toString(), pubchemDS, genesDone, linksDone);
       addXRef(database, ref, rootNode.chemspider_id.toString(), chemspiderDS, genesDone, linksDone);
       String chebID = rootNode.chebi_id.toString().trim()
       if (chebID.startsWith("CHEBI:")) {
         addXRef(database, ref, chebID, chebiDS, genesDone, linksDone);
         addXRef(database, ref, chebID.substring(6), chebiDS, genesDone, linksDone);
       } else if (chebID.length() > 0) {
         addXRef(database, ref, chebID, chebiDS, genesDone, linksDone);
         addXRef(database, ref, "CHEBI:" + chebID, chebiDS, genesDone, linksDone);
       }
       String keggID = rootNode.kegg_id.toString();
       if (keggID.length() > 0 && keggID.charAt(0) == 'C') {
         if (!blacklist.contains(keggID)) {
           addXRef(database, ref, keggID, keggDS, genesDone, linksDone);
         } else {
           println "Warn: No external IDs added for: " + keggID
         }
       } else if (keggID.length() > 0 && keggID.charAt(0) == 'D') {
         addXRef(database, ref, keggID, keggDrugDS, genesDone, linksDone);
       }
//      addXRef(database, ref, rootNode.nugowiki.toString(), nugoDS);
//      addXRef(database, ref, rootNode.drugbank_id.toString(), drugbankDS);
//      addXRef(database, ref, rootNode.inchi.toString(), inchiDS);
     } else {
       println "Warn: No external IDs added for: " + rootid
     }

     if (error > 0) println "errors: " + error + " (HMDB: ${entry.name})"
     counter++
     if (counter % commitInterval == 0) database.commit()
  }
}

// load the ChEBI content
counter = 0
// load the names
def chebiNames = new File('data/chebi_names.tsv')
chebiNames.eachLine { line,number ->
  if (number == 1) return // skip the first line

  error = 0
  columns = line.split('\t')
  shortid = columns[1]
  rootid = "CHEBI:" + shortid
  name = columns[4]
  // println rootid + " -> " + name
  Xref shortRef = new Xref(shortid, BioDataSource.CHEBI);
  if (!genesDone.contains(shortRef.toString())) {
    addError = database.addGene(shortRef);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError += database.addLink(shortRef,shortRef);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(shortRef.toString())
  }
  Xref ref = new Xref(rootid, BioDataSource.CHEBI);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError += database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }
  addAttribute(database, ref, "Symbol", name);

  if (error > 0) println "errors: " + error + " (ChEBI: $rootid)"
  counter++
  if (counter % commitInterval == 0) database.commit()
}
// load the mappings
def mappedIDs = new File('data/chebi_database_accession.tsv')
mappedIDs.eachLine { line,number ->
  if (number == 1) return // skip the first line

  columns = line.split('\t')
  rootid = "CHEBI:" + columns[1]
  type = columns[3]
  error = 0
  if (!blacklist.contains(rootid)) {
    id = columns[4]
    println "$rootid -($type)-> $id"
    Xref ref = new Xref(rootid, BioDataSource.CHEBI);
    if (type == "CAS Registry Number") {
      if (!id.contains(" ") && !id.contains(":") && id.contains("-")) {
        addXRef(database, ref, id, BioDataSource.CAS, genesDone, linksDone);
      }
    } else if (type == "KEGG COMPOUND accession") {
      addXRef(database, ref, id, BioDataSource.KEGG_COMPOUND, genesDone, linksDone);
    } else if (type == "Chemspider accession") {
      addXRef(database, ref, id, chemspiderDS, genesDone, linksDone);
    } else if (type == "Pubchem accession") {
      addXRef(database, ref, id, pubchemDS, genesDone, linksDone);
    } else if (type == "LIPID MAPS class accession") {
      addXRef(database, ref, id, lmDS, genesDone, linksDone);
    } else if (type == "LIPID MAPS instance accession") {
      addXRef(database, ref, id, lmDS, genesDone, linksDone);
    } else if (type == "KNApSAcK accession") {
      addXRef(database, ref, id, knapsackDS, genesDone, linksDone);
    }
  } else {
    println "No external IDs added for: " + rootid
  }
  counter++
  if (error > 0) println "errors: " + error + " (ChEBI: $rootid)"
  if (counter % commitInterval == 0) {
    database.commit()
  }
}

// load the Wikidata content

// CAS registry numbers
counter = 0
error = 0
new File("cas2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  addXRef(database, ref, fields[1], casDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (CAS)"
    database.commit()
  }
}

// PubChem registry numbers
counter = 0
error = 0
new File("pubchem2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  addXRef(database, ref, fields[1], pubchemDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (PubChem)"
    database.commit()
  }
}

// KEGG registry numbers
counter = 0
error = 0
new File("kegg2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  keggID = fields[1]
  if (keggID.charAt(0) == 'C') {
    addXRef(database, ref, keggID, keggDS, genesDone, linksDone);
  } else if (keggID.charAt(0) == 'D') {
    addXRef(database, ref, keggID, keggDrugDS, genesDone, linksDone);
  } else {
    println "unclear KEGG ID ($rootid): " + keggID
  }
  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (KEGG)"
    database.commit()
  }
}

// ChemSpider registry numbers
counter = 0
error = 0
new File("cs2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  addXRef(database, ref, fields[1], chemspiderDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (ChemSpider)"
    database.commit()
  }
}

// LIPID MAPS registry numbers
counter = 0
error = 0
new File("lm2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  addXRef(database, ref, fields[1], lmDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (LIPIDMAPS)"
    database.commit()
  }
}

// HMDB registry numbers
counter = 0
error = 0
new File("hmdb2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  addXRef(database, ref, fields[1], BioDataSource.HMDB, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (HMDB)"
    database.commit()
  }
}

// EPA CompTox Dashboard numbers
counter = 0
error = 0
new File("comptox2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  // addXRef(database, ref, fields[1], dtxDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (EPA CompTox Dashboard)"
    database.commit()
  }
}

// ChEBI registry numbers
counter = 0
error = 0
new File("chebi2wikidata.csv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split(",")
  rootid = fields[0].substring(31)
  Xref ref = new Xref(rootid, wikidataDS);
  if (!genesDone.contains(ref.toString())) {
    addError = database.addGene(ref);
    if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
    error += addError
    linkError = database.addLink(ref,ref);
    if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
    error += linkError
    genesDone.add(ref.toString())
  }

  // add external identifiers
  shortid = fields[1]
  chebiid = "CHEBI:" + shortid
  Xref chebiRef = new Xref(rootid, BioDataSource.CHEBI);
  addXRef(database, ref, shortid, BioDataSource.CHEBI, genesDone, linksDone);
  addXRef(database, ref, chebiid, BioDataSource.CHEBI, genesDone, linksDone);
  addXRef(database, chebiRef, rootid, wikidataDS, genesDone, linksDone);

  counter++
  if (counter % commitInterval == 0) {
    println "Info: errors: " + error + " (ChEBI)"
    database.commit()
  }
}

// Wikidata names
counter = 0
error = 0
new File("names2wikidata.tsv").eachLine { line,number ->
  if (number == 1) return // skip the first line

  fields = line.split("\t")
  if (fields.length >= 3) {
    rootid = fields[0].substring(31)
    key = fields[1].trim()
    synonym = fields[2].trim()
    Xref ref = new Xref(rootid, wikidataDS);
    if (!genesDone.contains(ref.toString())) {
      addError = database.addGene(ref);
      if (addError != 0) println "Error (addGene): " + database.recentException().getMessage()
      error += addError
      linkError = database.addLink(ref,ref);
      if (linkError != 0) println "Error (addLinkItself): " + database.recentException().getMessage()
      error += linkError
      genesDone.add(ref.toString())
    }
    if (synonym.length() > 0) {
      addAttribute(database, ref, "Symbol", synonym)
      addXRef(database, ref, key, inchikeyDS, genesDone, linksDone);
    }
    if (key.length() > 0) {
      addAttribute(database, ref, "InChIKey", key);
    }
  }
  counter++
  if (counter % commitInterval == 0) {
    println "errors: " + error + " (label)"
    database.commit()
  }
}

database.commit();
database.finalize();