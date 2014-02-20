package org.hive2hive.core.processes.implementations.files.add;

import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import org.hive2hive.core.file.FileUtil;
import org.hive2hive.core.log.H2HLogger;
import org.hive2hive.core.log.H2HLoggerFactory;
import org.hive2hive.core.model.FileVersion;
import org.hive2hive.core.model.MetaFile;
import org.hive2hive.core.processes.framework.abstracts.ProcessStep;
import org.hive2hive.core.processes.framework.exceptions.InvalidProcessStateException;
import org.hive2hive.core.processes.implementations.context.AddFileProcessContext;

/**
 * Create a new {@link MetaDocument}.
 * 
 * @author Nico, Chris
 */
public class CreateMetaFileStep extends ProcessStep {

	private static final H2HLogger logger = H2HLoggerFactory.getLogger(CreateMetaFileStep.class);
	private final AddFileProcessContext context;

	public CreateMetaFileStep(AddFileProcessContext context) {
		this.context = context;
	}

	@Override
	protected void doExecute() throws InvalidProcessStateException {
		File file = context.getFile();
		KeyPair metaKeyPair = context.getNewMetaKeyPair();

		// create new meta file with new version
		FileVersion version = new FileVersion(0, FileUtil.getFileSize(file), System.currentTimeMillis(),
				context.getChunkIds());
		List<FileVersion> versions = new ArrayList<FileVersion>(1);
		versions.add(version);

		MetaFile metaFile = new MetaFile(metaKeyPair.getPublic(), file.getName(), versions,
				context.getChunkEncryptionKeys());
		logger.debug(String.format("New meta file created. file = '%s'", file.getName()));

		context.provideNewMetaFile(metaFile);
	}
}
