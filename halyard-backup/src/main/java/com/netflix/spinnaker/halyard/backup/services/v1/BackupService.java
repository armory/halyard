/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.halyard.backup.services.v1;

import com.netflix.spinnaker.halyard.config.config.v1.HalconfigDirectoryStructure;
import com.netflix.spinnaker.halyard.config.config.v1.HalconfigParser;
import com.netflix.spinnaker.halyard.config.model.v1.node.Halconfig;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Component
@Slf4j
public class BackupService {
  @Autowired
  HalconfigParser halconfigParser;

  @Autowired
  HalconfigDirectoryStructure directoryStructure;

  static String[] omitPaths = {"service-logs"};

  public void restore(String backupTar) {
    String halconfigDir = directoryStructure.getHalconfigDirectory();
    untarHalconfig(halconfigDir, backupTar);

    Halconfig halconfig = halconfigParser.getHalconfig();
    halconfig.makeLocalFilesAbsolute(halconfigDir);
    halconfigParser.saveConfig();
  }

  public String create(String tarOutputName, String halconfigTar) {
    String halconfigDir = directoryStructure.getHalconfigDirectory();
    halconfigParser.backupConfig();
    Halconfig halconfig = halconfigParser.getHalconfig();
    halconfig.backupLocalFiles(directoryStructure.getBackupConfigDependenciesPath().toString());
    halconfig.makeLocalFilesRelative(halconfigDir);
    halconfigParser.saveConfig();

    try {
      tarHalconfig(halconfigDir, halconfigTar);
    } catch (IOException e) {
      throw new HalException(Problem.Severity.FATAL, "Unable to safely backup halconfig " + e.getMessage(), e);
    } finally {
      halconfigParser.switchToBackupConfig();
      halconfigParser.getHalconfig();
      halconfigParser.saveConfig();
      halconfigParser.switchToPrimaryConfig();
    }

    return halconfigTar;
  }
  public String create(String tarOutputName) {
    return create(tarOutputName, Paths.get(System.getProperty("user.home"), tarOutputName).toString());
  }
  public String create() {
    String tarOutputName = String.format("halbackup-%s.tar", new Date()).replace(" ", "_").replace(":", "-");
    return create(tarOutputName);
  }
  public String createIn(Path path) {
    String tarOutputName = String.format("halbackup-%s.tar", new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
    return create(tarOutputName, Paths.get(path.toString(), tarOutputName).toString());
  }


  private void untarHalconfig(String halconfigDir, String halconfigTar) {
    FileInputStream tarInput = null;
    TarArchiveInputStream tarArchiveInputStream = null;

    try {
      tarInput = new FileInputStream(new File(halconfigTar));
      tarArchiveInputStream = (TarArchiveInputStream) new ArchiveStreamFactory()
          .createArchiveInputStream("tar", tarInput);

    } catch (IOException | ArchiveException e) {
      throw new HalException(Problem.Severity.FATAL, "Failed to open backup: " + e.getMessage(), e);
    }

    try {
      ArchiveEntry archiveEntry = tarArchiveInputStream.getNextEntry();
      while (archiveEntry != null) {
        String entryName = archiveEntry.getName();
        Path outputPath = Paths.get(halconfigDir, entryName);
        File outputFile = outputPath.toFile();
        if (!outputFile.getParentFile().exists()) {
          outputFile.getParentFile().mkdirs();
        }

        if (archiveEntry.isDirectory()) {
          outputFile.mkdir();
        } else {
          Files.copy(tarArchiveInputStream, outputPath, REPLACE_EXISTING);
        }

        archiveEntry = tarArchiveInputStream.getNextEntry();
      }
    } catch (IOException e) {
      throw new HalException(Problem.Severity.FATAL, "Failed to read archive entry: " + e.getMessage(), e);
    }
  }

  private void tarHalconfig(String halconfigDir, String halconfigTar) throws IOException {
    FileOutputStream tarOutput = null;
    BufferedOutputStream bufferedTarOutput = null;
    TarArchiveOutputStream tarArchiveOutputStream = null;
    IOException fatalCleanup = null;
    List<String> hiddenFilesToInclude = Arrays.asList(".backup", ".boms");
    try {
      tarOutput = new FileOutputStream(new File(halconfigTar));
      bufferedTarOutput = new BufferedOutputStream(tarOutput);
      tarArchiveOutputStream = new TarArchiveOutputStream(bufferedTarOutput);
      TarArchiveOutputStream finalTarArchiveOutputStream = tarArchiveOutputStream;
      Arrays.stream(new File(halconfigDir).listFiles())
          .filter(Objects::nonNull)
          .filter(f -> !f.getName().startsWith(".") || hiddenFilesToInclude.contains(f.getName()))
          .forEach(f -> addFileToTar(finalTarArchiveOutputStream, f.getAbsolutePath(), ""));
    } catch (HalException e) {
      log.info("HalException caught during tar operation", e);
      throw e;
    } catch (IOException e) {
      log.info("IOException caught during tar operation", e);
      throw new HalException(Problem.Severity.FATAL, "Failed to backup halconfig: " + e.getMessage(), e);
    } finally {
      if (tarArchiveOutputStream != null) {
        try {
          tarArchiveOutputStream.finish();
          tarArchiveOutputStream.close();
        } catch (IOException e) {
          fatalCleanup = e;
        }
      }

      if (bufferedTarOutput != null) {
        bufferedTarOutput.close();
      }

      if (tarOutput != null) {
        tarOutput.close();
      }
    }

    if (fatalCleanup != null) {
      throw fatalCleanup;
    }
  }

  private void addFileToTar(TarArchiveOutputStream tarArchiveOutputStream, String path, String base) {
    File file = new File(path);
    String fileName = file.getName();

    if (Arrays.stream(omitPaths).anyMatch(s -> s.equals(fileName))) {
      return;
    }

    String tarEntryName = String.join("/", base, fileName);
    try {
      if (file.isFile()) {
        TarArchiveEntry tarEntry = new TarArchiveEntry(file, tarEntryName);
        tarArchiveOutputStream.putArchiveEntry(tarEntry);
        IOUtils.copy(new FileInputStream(file), tarArchiveOutputStream);
        tarArchiveOutputStream.closeArchiveEntry();
      } else if (file.isDirectory()) {
        Arrays.stream(file.listFiles())
            .filter(Objects::nonNull)
            .forEach(f -> addFileToTar(tarArchiveOutputStream, f.getAbsolutePath(), tarEntryName));
      } else {
        log.warn("Unknown file type: " + file + " - skipping addition to tar archive");
      }
    } catch (IOException e) {
      throw new HalException(Problem.Severity.FATAL, "Unable to file " + file.getName() + " to archive entry: " + tarEntryName + " " + e.getMessage(), e);
    }
  }
}
