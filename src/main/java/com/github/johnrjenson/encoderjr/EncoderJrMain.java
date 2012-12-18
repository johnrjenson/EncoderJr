package com.github.johnrjenson.encoderjr;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author jensonjr
 */
public class EncoderJrMain implements Runnable {

	private static String ORIGINALS_DIR_NAME;
	private static String ORIGINALS_EXT = ".MOV";

	private static boolean debug = false;

	public static void main(String args[]) throws IOException, InterruptedException, ParseException {
		Properties autoEncodeProperties = new Properties();
		InputStream in = null;
		try {
			in = new FileInputStream("encoderJr.properties");
			autoEncodeProperties.load(in);
		} finally {
			if(in != null) {
				in.close();
			}
		}

		if(args.length >= 1 && args[0] != null && args[0].equals("-debug")) {
			debug = true;
		}

		if(args.length >= 1 && args[0] != null && args[0].equals("-lastModified")) {
			Path file = Paths.get(args[1]);
			Files.setLastModifiedTime(file, FileTime.fromMillis(new SimpleDateFormat("MM dd yyyy hh:mm").parse(args[2]).getTime()));
			System.exit(0);
			return;
		}

		String userDir = System.getProperty("user.home");
		String dirsString = autoEncodeProperties.getProperty("movie.dirs.to.scan");
		userDir = userDir.replaceAll("\\\\", "\\\\\\\\");
		dirsString = dirsString.replaceAll("\\{user\\.home\\}", userDir);
		System.out.println(dirsString);
		String watchDirs[] = dirsString.split(";");

		EncoderJrMain encoder = new EncoderJrMain(watchDirs, autoEncodeProperties);
		if(debug) {
			encoder.run();
		} else {
			Thread daemon = new Thread(encoder);
			daemon.start();
			System.out.println("MovieEncoder is running...");
		}
	}

	private final Properties autoEncodeProperties;
	private final Map<WatchKey,Path> pathsByWatchKey;
	private final Map<Path,WatchKey> watchKeysByPath;
	private final WatchService watchService;

	public EncoderJrMain(String watchDirs[], Properties autoEncodeProperties) throws IOException, InterruptedException {
		watchService = FileSystems.getDefault().newWatchService();
		pathsByWatchKey = new HashMap<WatchKey,Path>();
		watchKeysByPath = new HashMap<Path,WatchKey>();
		this.autoEncodeProperties = autoEncodeProperties;
		ORIGINALS_DIR_NAME = autoEncodeProperties.getProperty("originals.dir.name");

		for (String watchDirPath : watchDirs) {
			Path path = Paths.get(watchDirPath);
			List<Path> files = registerDirTree(path);
			for (Path file : files) {
				encodeMovieIfNeeded(file);
			}
		}
	}

	private List<Path> registerDirTree(Path dir) throws IOException {
		final List<Path> files = new ArrayList<Path>();
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if(watchKeysByPath.get(dir) == null && ! dir.endsWith(ORIGINALS_DIR_NAME)) {
					registerDir(dir);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String pathToFile = file.toString();
				if(pathToFile.indexOf(ORIGINALS_DIR_NAME) == -1 && pathToFile.toUpperCase().endsWith(ORIGINALS_EXT.toUpperCase())) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});

		return files;
	}
	private void registerDir(Path dir) throws IOException {
		if(debug) {
			System.out.println(dir);
		}
		WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE);
		pathsByWatchKey.put(key, dir);
		watchKeysByPath.put(dir, key);
	}
	private void removeDir(Path dir) throws IOException {
		WatchKey key = watchKeysByPath.remove(dir);
		if(key != null) {
			pathsByWatchKey.remove(key);
			key.cancel();
		}
	}

	public void run() {
		while (true) {

			// wait for key to be signalled
			WatchKey key;
			try {
				key = watchService.take(); // blocking call
			} catch (InterruptedException x) {
				x.printStackTrace();
				return;
			}

			Path dir = pathsByWatchKey.get(key);
			if (dir == null) {
				if(debug) {
					System.out.format("WatchKey not recognized: %s\n", key);
					System.out.format("WatchKey events: %s\n", key.pollEvents());
				}
				continue;
			}

			List<WatchEvent<?>>  events = key.pollEvents();
			for (WatchEvent<?> event: events) {
				WatchEvent.Kind kind = event.kind();

				// TBD - provide example of how OVERFLOW event is handled
				if (kind == OVERFLOW) {
					if(debug) {
						System.out.format("OVERFLOW\n", key);
					}
					continue;
				}

				// Context for directory entry event is the file name of entry
				WatchEvent<Path> ev = castWatchEvent(event);
				Path name = ev.context();
				Path child = dir.resolve(name);

				// print out event

				if ((kind == ENTRY_CREATE)) {
					try {
						// if directory is created, and watching recursively, then
						// register it and its sub-directories
						if(Files.isDirectory(child)) {
							List<Path> files = registerDirTree(child);
							if(debug) {
								System.out.format("Directory was created: %s: %s\n", event.kind().name(), child);
							}
							for (Path file : files) {
								encodeMovieIfNeeded(file);
							}
						} else {
							if(debug) {
								System.out.format("File was created: %s: %s\n", event.kind().name(), child);
							}
							encodeMovieIfNeeded(child);
						}
					} catch (IOException x) {
						x.printStackTrace();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
/*
				if ((kind == ENTRY_MODIFY)) {
					if(! Files.isDirectory(child)) {
						System.out.format("File was modified: %s: %s - %s - %s - %s\n", event.kind().name(), child, dir, ev.count(), child.toFile().lastModified());
					}
				}
*/
				if ((kind == ENTRY_DELETE)) {
					try {
						removeDir(child);
					} catch (IOException x) {
						x.printStackTrace();
					}
				}
			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				Path d = pathsByWatchKey.remove(key);
				if(d != null) {
					watchKeysByPath.remove(d);
				}
			}

		}
	}

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> castWatchEvent(WatchEvent<?> event) {
		return (WatchEvent<T>)event;
	}

	private void encodeMovieIfNeeded(Path originalFile) throws IOException, InterruptedException {
		String originalFileName = originalFile.getFileName().toString();
		if(originalFileName.toUpperCase().endsWith(ORIGINALS_EXT)) {
			Path parentDir = originalFile.getParent();
			Path targetFile = parentDir.resolve(originalFileName.replaceAll("\\"+ORIGINALS_EXT.toLowerCase(), ".m4v").replaceAll("\\"+ORIGINALS_EXT.toUpperCase(), ".m4v"));
			if(! targetFile.toFile().exists()) {
				// the file might not be completely there yet especially if it is large and takes a while to copy from the SD card
				waitForFileToBeAvailable(originalFile);

				Path tempFile = Paths.get(originalFile.toString()+".encoding.tmp");

				// build and execute command
				String encoderCommand = buildEncoderCommand(originalFile, tempFile);
				int exitCode = executeEncoderCommand(encoderCommand);

				// rename temp file
				FileTime lastModifiedTime = Files.getLastModifiedTime(originalFile);
				targetFile = Files.move(tempFile, targetFile);
				Files.setLastModifiedTime(targetFile, lastModifiedTime);

				// move the original to subdir called 'originals'
				Path originalsDir = originalFile.getParent().resolve(ORIGINALS_DIR_NAME);
				originalsDir = Files.createDirectories(originalsDir);
				Path movedOriginal = Files.move(originalFile, originalsDir.resolve(originalFileName));
				Files.setLastModifiedTime(movedOriginal, lastModifiedTime);

				if(debug) {
					System.out.format("targetFile.toFile().lastModified(): %s\n", targetFile.toFile().lastModified());
					System.out.format("movedOriginal.toFile().lastModified(): %s\n", movedOriginal.toFile().lastModified());
					System.out.format("Finished with exit code %s: %s\n", exitCode, encoderCommand);
				}
			} else {
				if(debug) {
					System.out.format("File already exists: %s\n", targetFile);
				}
			}
		}
	}

	private void waitForFileToBeAvailable(Path file) throws IOException, InterruptedException {
		boolean fileInUse = true;
		while(fileInUse) {
			try {
				FileInputStream in = new FileInputStream(file.toString());
				in.close();
				fileInUse = false;
			} catch (FileNotFoundException e) {
				if(e.getMessage().indexOf("used by another process") >= 0) {
					if(debug) {
						System.out.println("Waiting for "+file);
					}
					fileInUse = true;
					Thread.sleep(1000);
				} else {
					throw e;
				}
			}
		}
	}

	private String buildEncoderCommand(Path originalFile, Path targetFile) {
		String encoderCommand = autoEncodeProperties.getProperty("encoder.command")
				.replaceAll("\\{source\\}", "\""+originalFile.toString().replaceAll("\\\\", "\\\\\\\\")+"\"")
				.replaceAll("\\{destination\\}", "\""+targetFile.toString().replaceAll("\\\\", "\\\\\\\\")+"\"");

		if(debug) {
			System.out.println(encoderCommand);
		}

		return encoderCommand;
	}

	private int executeEncoderCommand(String handbreakCommand) throws InterruptedException, IOException {
		Process process = Runtime.getRuntime().exec(handbreakCommand);
		return logOutputAndWaitForTermination(process);
	}

	private int logOutputAndWaitForTermination(Process process) throws InterruptedException
	{
		new Thread(new OutputWriter(System.out, process.getInputStream())).start();
		new Thread(new OutputWriter(System.err, process.getErrorStream())).start();

		return process.waitFor();
	}

	private class OutputWriter implements Runnable
	{
		private OutputStream outputStream;
		private InputStream inputStream;

		public OutputWriter(OutputStream outputStream, InputStream inputStream)
		{
			this.outputStream = outputStream;
			this.inputStream = inputStream;
		}

		public void run()
		{
			try
			{
				byte[] buff = new byte[1024];
				int bytesRead;
				while((bytesRead = inputStream.read(buff)) >=0)
				{
					if(debug)
					{
						outputStream.write(buff, 0, bytesRead);
					}
				}
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}

}
