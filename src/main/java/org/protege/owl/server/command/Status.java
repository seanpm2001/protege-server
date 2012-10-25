package org.protege.owl.server.command;

import static org.protege.owl.server.command.P4OWLServerOptions.NEEDS_HELP_OPTION;

import java.io.Console;
import java.io.File;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.protege.owl.server.api.ChangeMetaData;
import org.protege.owl.server.api.Client;
import org.protege.owl.server.api.RevisionPointer;
import org.protege.owl.server.api.VersionedOntologyDocument;
import org.protege.owl.server.util.ClientRegistry;
import org.protege.owl.server.util.ClientUtilities;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

public class Status extends ServerCommand {
    private Options options = new Options();
    {
        options.addOption(NEEDS_HELP_OPTION);
    }
    private File ontologyFile;

    @Override
    public boolean parse(String[] args) throws ParseException {
        boolean goForIt = false;
        CommandLine cmd = new GnuParser().parse(options, args, true);
        String[] remainingArgs = cmd.getArgs();
        if (!needsHelp(cmd) && remainingArgs.length == 1) {
            ontologyFile = new File(remainingArgs[0]);
            goForIt = true;
        }
        if (ontologyFile != null && !ontologyFile.exists()) {
            System.out.println("File " + ontologyFile + " not found so the commit status cannot be calculated.");
            goForIt = false;
        }
        return goForIt;
    }

    @Override
    public void execute() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntology(IRI.create(ontologyFile));
        ClientRegistry registry = getClientRegistry();
        if (registry.hasSuitableMetaData(ontology)) {
            Client client = registry.connectToServer(ontology);
            VersionedOntologyDocument vont = registry.getVersionedOntologyDocument(ontology);
            System.out.println("Server Location: " + vont.getServerDocument().getServerLocation());
            System.out.println("Local Revision: " + vont.getRevision());
            System.out.println("Latest server revision: " + client.evaluateRevisionPointer(vont.getServerDocument(), RevisionPointer.HEAD_REVISION));
        }
        else {
            System.out.println("Could not connect to appropriate server - no known server metadata found.");
        }
    }

    @Override
    public void usage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(80, "Status <options> ontology-file", "", options, "");
    }

    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        new Status().run(args);
    }

}
