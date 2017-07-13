package edu.ucsf.rbvi.stringApp.internal.tasks;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.cytoscape.application.swing.search.AbstractNetworkSearchTaskFactory;
import org.cytoscape.work.FinishStatus;
import org.cytoscape.work.ObservableTask;
import org.cytoscape.work.TaskFactory;
import org.cytoscape.work.TaskIterator;
import org.cytoscape.work.TaskObserver;
import org.cytoscape.io.webservice.NetworkImportWebServiceClient;
import org.cytoscape.io.webservice.SearchWebServiceClient;
import org.cytoscape.io.webservice.swing.AbstractWebServiceGUIClient;

import edu.ucsf.rbvi.stringApp.internal.model.Databases;
import edu.ucsf.rbvi.stringApp.internal.model.StringManager;
import edu.ucsf.rbvi.stringApp.internal.model.StringNetwork;
import edu.ucsf.rbvi.stringApp.internal.tasks.GetAnnotationsTask;
import edu.ucsf.rbvi.stringApp.internal.tasks.ImportNetworkTaskFactory;
import edu.ucsf.rbvi.stringApp.internal.ui.GetTermsPanel;
import edu.ucsf.rbvi.stringApp.internal.ui.SearchOptionsPanel;
import edu.ucsf.rbvi.stringApp.internal.utils.ModelUtils;

public class StringSearchTaskFactory extends AbstractNetworkSearchTaskFactory implements TaskObserver {
	StringManager manager;
	static String STRING_ID = "edu.ucsf.rbvi.string";
	static String STRING_URL = "http://string-db.org";
	static String STRING_NAME = "STRING protein query";
	static String STRING_DESC = "Search STRING for protein-protein interactions";
	static String STRING_DESC_LONG = "<html>STRING is a database of known and predicted protein interactions.  The interactions include direct (physical) and indirect (functional) associations; they are derived from four sources: <ul><li>Genomic Context</li><li>High-throughput Experiments</li><li>(Conserved) Coexpression</li><li>Previous Knowledge</li></ul>	 STRING quantitatively integrates interaction data from these sources for a large number of organisms, and transfers information between these organisms where applicable. The database currently covers 9,643,763 proteins from 2,031 organisms.</html>";

	private StringNetwork stringNetwork = null;
	private SearchOptionsPanel optionsPanel = null;

	private static final Icon icon = new ImageIcon(
      StringSearchTaskFactory.class.getResource("/images/string_logo.png"));

	private static URL stringURL() {
		try {
			return new URL(STRING_URL);
		} catch (MalformedURLException e) {
			return null;
		}
	}

	public StringSearchTaskFactory(StringManager manager) {
		super(STRING_ID, STRING_NAME, STRING_DESC, icon, StringSearchTaskFactory.stringURL());
		this.manager = manager;
	}

	public boolean isReady() { return true; }

	public TaskIterator createTaskIterator() {
		String terms = getQuery();

		if (terms == null) {
			throw new NullPointerException("Query string is null.");
		}

		stringNetwork = new StringNetwork(manager);
		int taxon = getTaxId();

		terms = ModelUtils.convertTerms(terms, true, true);

		return new TaskIterator(new GetAnnotationsTask(stringNetwork, taxon, terms, Databases.STRING.getAPIName()));
	}

	@Override
	public String getName() { return STRING_NAME; }

	@Override
	public String getId() { return STRING_ID; }

	@Override
	public String getDescription() {
		return STRING_DESC_LONG;
	}

	@Override
	public Icon getIcon() {
		return icon;
	}

	@Override
	public URL getWebsite() { 
		return StringSearchTaskFactory.stringURL();
	}

	// Create a JPanel that provides the species, confidence interval, and number of interactions
	// NOTE: we need to use reasonable defaults since it's likely the user won't actually change it...
	@Override
	public JComponent getOptionsComponent() {
		optionsPanel = new SearchOptionsPanel(manager);
		return optionsPanel;
	}

	@Override
	public JComponent getQueryComponent() {
		return null;
	}

	@Override
	public TaskObserver getTaskObserver() { return this; }

	public int getTaxId() {
		// This will eventually come from the OptionsComponent...
		if (optionsPanel.getSpecies() != null)
			return optionsPanel.getSpecies().getTaxId();
		return 9606; // Homo sapiens
	}

	public String getSpecies() {
		// This will eventually come from the OptionsComponent...
		if (optionsPanel.getSpecies() != null)
			return optionsPanel.getSpecies().toString();
		return "Homo sapiens"; // Homo sapiens
	}

	public int getAdditionalNodes() {
		// This will eventually come from the OptionsComponent...
		return optionsPanel.getAdditionalNodes();
	}

	public int getConfidence() {
		// This will eventually come from the OptionsComponent...
		return optionsPanel.getConfidence();
	}

	@Override
	public void allFinished(FinishStatus finishStatus) {
	}

	@Override
	public void taskFinished(ObservableTask task) {
		if (!(task instanceof GetAnnotationsTask)) {
			return;
		}
		GetAnnotationsTask annTask = (GetAnnotationsTask)task;

		final int taxon = annTask.getTaxon();
		if (stringNetwork.getAnnotations() == null || stringNetwork.getAnnotations().size() == 0) {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					JOptionPane.showMessageDialog(null, "Your query returned no results",
								                        "No results", JOptionPane.ERROR_MESSAGE); 
				}
			});
			return;
		}
		boolean noAmbiguity = stringNetwork.resolveAnnotations();
		if (noAmbiguity) {
			int additionalNodes = getAdditionalNodes();
			// This mimics the String web site behavior
			if (stringNetwork.getResolvedTerms() == 1 && additionalNodes == 0) {
				SwingUtilities.invokeLater(new Runnable() {
					public void run() {
						JOptionPane.showMessageDialog(null, 
												"This will return only one node (Hint: increase maximum interactors slider?)",
									       "Hint", JOptionPane.WARNING_MESSAGE); 
					}
				});
			}
			//	additionalNodes = 10;

			final int addNodes = additionalNodes;

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					importNetwork(taxon, getConfidence(), addNodes);
				}
			});
		} else {
			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					JDialog d = new JDialog();
					d.setTitle("Resolve Ambiguous Terms");
					GetTermsPanel panel = new GetTermsPanel(manager, stringNetwork, Databases.STRING.getAPIName(), 
					                                        getSpecies(), false, getConfidence(), getAdditionalNodes());
					panel.createResolutionPanel();
					d.setContentPane(panel);
					d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
					d.pack();
					d.setVisible(true);
				}
			});
		}
	}

	void importNetwork(int taxon, int confidence, int additionalNodes) {
		Map<String, String> queryTermMap = new HashMap<>();
		List<String> stringIds = stringNetwork.combineIds(queryTermMap);
		// System.out.println("Importing "+stringIds);
		TaskFactory factory = new ImportNetworkTaskFactory(stringNetwork, getSpecies(), 
		                                                   taxon, confidence, additionalNodes, stringIds,
																											 queryTermMap, Databases.STRING.getAPIName());
		manager.execute(factory.createTaskIterator());
	}

}