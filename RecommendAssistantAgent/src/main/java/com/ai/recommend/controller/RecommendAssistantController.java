package com.ai.recommend.controller;

import com.ai.recommend.Service.RecommendAssistant;
import com.ai.recommend.Service.Impl.IngestGuideDataServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Title Local rag controller.<br>
 * Description Local rag controller.<br>
 *
 * @author yuanci.ytb
 * @since 1.0.0-M2
 */

@RestController
@RequestMapping("/api/assistant")
public class RecommendAssistantController {


    @Resource
    private RecommendAssistant recommendAssistant;
    @Resource
    private IngestGuideDataServiceImpl ingestGuideDataService;


    @GetMapping("/rag/importDocument")
	public void importDocument() {
		ingestGuideDataService.ingestGuideData();
	}


    @RequestMapping(path="/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam(name = "userMessage") String message) {
        ToolCallback documentSearchCallback = FunctionToolCallback.builder("document_search",
                        (Function<RecommendAssistant.Request, RecommendAssistant.Response>)
                                req -> recommendAssistant.travelQuery(req))
                .description("搜索文档库")
                .inputType(RecommendAssistant.Request.class)
                .build();
        //todo
            return null;

    }
}
