package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.service.AnalysisService;
import com.traceguard.service.ChunkUploadService;
import com.traceguard.service.ProjectService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大文件分片上传（断点续传）：
 * init 初始化（或复用旧会话）-> chunk 逐片上传（失败可重试单片）-> complete 合并并落库。
 * 前端仅对小文件直传，超过阈值的大文件走本组接口。
 */
@RestController
@RequestMapping("/analysis/upload/chunk")
@Api(tags = "05-大文件分片上传")
public class ChunkUploadController {

    @Autowired
    private ChunkUploadService chunkUploadService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectService projectService;

    /** 初始化分片上传会话；传入旧 uploadId 可复用会话实现断点续传 */
    @ApiOperation(value = "初始化分片上传会话", notes = "分片总数必须大于0；传入旧 uploadId 可复用会话实现断点续传")
    @PostMapping("/init")
    public Result<Map<String, Object>> init(@RequestParam String filename,
                                            @RequestParam int totalChunks,
                                            @RequestParam(required = false) String uploadId) throws Exception {
        if (totalChunks <= 0) {
            return Result.error("分片总数必须大于 0");
        }
        String id = chunkUploadService.init(uploadId, filename, totalChunks);
        return Result.success(buildStatus(id));
    }

    /** 上传单个分片（chunkIndex 从 0 开始） */
    @ApiOperation(value = "上传单个分片", notes = "chunkIndex 从 0 开始；分片内容不能为空；失败可仅重试该单片")
    @PostMapping
    public Result<String> uploadChunk(@RequestParam String uploadId,
                                      @RequestParam int chunkIndex,
                                      @RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return Result.error("分片内容为空");
        }
        chunkUploadService.saveChunk(uploadId, chunkIndex, file);
        return Result.success("分片已接收");
    }

    /** 查询已上传分片（断点续传时前端据此跳过已传分片） */
    @ApiOperation(value = "查询已上传分片", notes = "返回已上传分片索引列表，断点续传时前端据此跳过已传分片")
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam String uploadId) {
        return Result.success(buildStatus(uploadId));
    }

    /** 全部分片到齐后合并并落库：type=requirement 需求文档 / type=code 代码工程(zip) */
    @ApiOperation(value = "合并分片并落库", notes = "全部分片到齐后合并、落库并清理上传会话；type=requirement 需求文档 / code 代码工程(zip)；需通过项目归属权限校验")
    @PostMapping("/complete")
    public Result<String> complete(@RequestParam String uploadId,
                                   @RequestParam Long projectId,
                                   @RequestParam String type) throws Exception {
        ChunkUploadService.MergedFile merged = null;
        projectService.checkOwnership(projectId);
        merged = chunkUploadService.merge(uploadId);
        String path;
        if ("requirement".equals(type)) {
            path = analysisService.saveMergedRequirement(merged.getFile(),
                    merged.getOriginalName(), projectId);
        } else if ("code".equals(type)) {
            path = analysisService.saveMergedCodeProject(merged.getFile(),
                    merged.getOriginalName(), projectId);
        } else {
            return Result.error("type 必须为 requirement 或 code");
        }
        chunkUploadService.cleanup(uploadId);
        return Result.success(path);
    }

    private Map<String, Object> buildStatus(String uploadId) {
        List<Integer> uploaded = chunkUploadService.uploadedChunks(uploadId);
        Map<String, Object> data = new HashMap<>();
        data.put("uploadId", uploadId);
        data.put("uploadedChunks", uploaded);
        return data;
    }
}
