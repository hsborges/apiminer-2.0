package org.apiminer.tasks.implementations;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apiminer.SystemProperties;
import org.apiminer.builder.IBuilder;
import org.apiminer.daos.DatabaseType;
import org.apiminer.daos.ExampleDAO;
import org.apiminer.daos.ProjectDAO;
import org.apiminer.entities.ProjectAnalyserStatistic;
import org.apiminer.entities.api.ApiClass;
import org.apiminer.entities.api.ApiElement;
import org.apiminer.entities.api.Project;
import org.apiminer.entities.api.ProjectStatus;
import org.apiminer.entities.api.Repository;
import org.apiminer.entities.api.RepositoryType;
import org.apiminer.entities.example.Example;
import org.apiminer.extractor.ExampleExtractor;
import org.apiminer.tasks.AbstractTask;
import org.apiminer.tasks.TaskResult;
import org.apiminer.tasks.TaskStatus;
import org.apiminer.util.BuilderUtil;
import org.apiminer.util.FilesUtil;
import org.apiminer.util.downloader.DownloaderFactory;

import uk.ac.shef.wit.simmetrics.similaritymetrics.AbstractStringMetric;
import uk.ac.shef.wit.simmetrics.similaritymetrics.CosineSimilarity;


public class ExampleExtractorTask extends AbstractTask {
	
	private Logger LOGGER = Logger.getLogger(ExampleExtractorTask.class);

	private Project project;

	private ExampleExtractorTask() {
		super();
		
		this.project = new Project();
		this.project.setAddedAt(new Date());
		this.project.setClientOf(new ProjectDAO().findSourceAPI());
	}

	public ExampleExtractorTask(String name,
			String summary,
			String url,
			ProjectStatus projectStatus,
			RepositoryType repositoryType,
			String urlRepository) {
		
		this();
		
		this.project.setName(name);
		this.project.setProjectStatus(projectStatus);
		this.project.setSummary(summary);
		this.project.setUrlSite(url);
		
		Repository repository = new Repository();
		repository.setRepositoryType(repositoryType);
		repository.setUrlAddress(urlRepository);
		
		this.project.setRepository(repository);
	}
	
	@Override
	public void execute() {
		super.setStatus(TaskStatus.RUNNING);
		
		try {
			if (new ProjectDAO().find(project.getName().trim(), DatabaseType.EXAMPLES) != null) {
				throw new IllegalArgumentException("Project already registred!");
			}

			Repository repository = project.getRepository();
			
			File localPathFile = null;
			
			LOGGER.debug("Downloading source files from repository");
			switch(repository.getRepositoryType()){
			
			case COMPRESSED:
				localPathFile = DownloaderFactory.getCompressedDownloader().download(project.getName(), repository.getUrlAddress(), SystemProperties.WORKING_DIRECTORY.getAbsolutePath());
				break;
			
			case GIT:
				localPathFile = DownloaderFactory.getGitDownloader().download(project.getName(), repository.getUrlAddress(), SystemProperties.WORKING_DIRECTORY.getAbsolutePath());
				break;
				
				
			case LOCAL:
				localPathFile = new File(repository.getUrlAddress());
				break;
			
			case MERCURIAL:
				localPathFile = DownloaderFactory.getMercurialDownloader().download(project.getName(), repository.getUrlAddress(), SystemProperties.WORKING_DIRECTORY.getAbsolutePath());
				break;
			
			case SUBVERSION:
				localPathFile = DownloaderFactory.getSubversionDownloader().download(project.getName(), repository.getUrlAddress(), SystemProperties.WORKING_DIRECTORY.getAbsolutePath());
				break;
			
			default:
				break;
			
			}
			
			repository.setSourceFilesDirectory(localPathFile.getAbsolutePath());
			repository.setJars(new HashSet<String>(FilesUtil.collectFiles(repository.getSourceFilesDirectory(), ".jar", true)));
			
			
			LOGGER.debug("Building files using default builders");
			Set<Class<? extends IBuilder>> defaultBuilders = BuilderUtil.getBuilders();
			for (Class<? extends IBuilder> defaultBuilder : defaultBuilders) {
				try {
					IBuilder builder = defaultBuilder.newInstance();
					LOGGER.debug("Using builder '"+builder.getBuilderName()+"'");
					if (builder.build(repository.getSourceFilesDirectory())) {
						LOGGER.debug("Sucess on build with '"+builder.getBuilderName()+"'");
					} else {
						LOGGER.debug("Fail to build with '"+builder.getBuilderName()+"'");
					}
				} catch (Exception e) {
					LOGGER.error(e);
				}
			}
			
			LOGGER.debug("Extracting the code examples");
			String sourceDirectory = project.getRepository().getSourceFilesDirectory();
			Set<String> jarsDependencies = project.getClientOf()
					.getRepository()
					.getJars();

			ExampleExtractor parser = new ExampleExtractor(
					sourceDirectory,
					jarsDependencies,
					project.getClientOf().getId());
			
			parser.parse();

			Set<ApiClass> apiClasses = parser.getApiClasses();
			Collection<Example> examples = parser.getExamples();
			
			LOGGER.debug("Removing similar examples");
			
			removeSimilarExamples(examples);

			project.getRepository().setJars(new HashSet<String>(parser.getJarsDependencies()));
			project.setApiClass(apiClasses);
			project.setStatistics(new ProjectAnalyserStatistic()); 
			
			LOGGER.debug("Persisting client " + project.getName());
			
			new ExampleDAO().persist(project, examples);
			
			super.setResult(TaskResult.SUCCESS);
		} catch (Throwable throwable) {
			super.setResult(throwable);;
		} finally {
			super.setStatus(TaskStatus.FINISHED);
		}
	}

	@Override
	public String toString() {
		return String.format("Extraction of code examples from client %s", project.getName());
	}
	
	private void removeSimilarExamples(final Collection<Example> examples){
		
		// Se a lista tem somente 1 exemplo, nao havera duplicacoes
		if (examples == null || examples.size() < 2) {
			return;
		}
		
		// Algoritmo para calculo da distância
		AbstractStringMetric metric = new CosineSimilarity();
		
		// Separo os exemplos por grupos, baseado nos elementos que sao baseados
		Map<Set<ApiElement>, List<Example>> groups = new HashMap<Set<ApiElement>, List<Example>>();
		for (Example ex : examples) {
			if (!groups.containsKey(ex.getApiMethods())) {
				groups.put(ex.getApiMethods(), new LinkedList<Example>());
			}
			groups.get(ex.getApiMethods()).add(ex);
		}
		
		// Limpa a lista recebida
		examples.clear();
		
		// Itera sobre os grupos buscando por exemplos duplicados
		for (Set<ApiElement> group : groups.keySet()) {
			
			Example[] examplesGroupArray = groups.get(group).toArray(new Example[0]);
			
			// Mapeia os exemplos em strings. Neste passo exemplos completamente iguais ja sao removidos.
			LinkedHashMap<String, Example> examplesMap = new LinkedHashMap<String, Example>();
			for (Example ex : examplesGroupArray) {
				if (!examplesMap.containsKey(ex.getCodeExample())) {
					examplesMap.put(ex.getCodeExample(), ex);
				}
			}
			
			// Remove exemplos semelhantes
			while(!examplesMap.isEmpty()) {

				String[] examplesStringArray = examplesMap.keySet().toArray(new String[0]);
				Example example = examplesMap.remove(examplesStringArray[0]);
				examples.add(example);
				
				float[] result = metric.batchCompareSet(examplesStringArray, example.getCodeExample());
				for (int j = 1; j < result.length; j++) {
					if (result[j] >= 0.8) {
						examplesMap.remove(examplesStringArray[j]);
					}
				}
			}
			
		}
		
	}

}
