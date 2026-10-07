package com.jisong.bgm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisong.bgm.model.Question;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class QuestionBank {
    private final ObjectMapper objectMapper;
    private final Path filePath;
    private List<Question> questions = List.of();

    public QuestionBank(
            ObjectMapper objectMapper,
            @Value("${app.questions-file:data/questions.json}") String filePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(filePath)) {
            seedDefaultQuestions();
        }
        try {
            questions = List.copyOf(objectMapper.readValue(
                    filePath.toFile(),
                    new TypeReference<List<Question>>() {
                    }
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("题库文件读取失败：" + filePath.toAbsolutePath()
                    + "，请检查 JSON 格式", exception);
        }
        validate();
    }

    public List<Question> questions() {
        return questions;
    }

    private void validate() {
        if (questions.isEmpty()) {
            throw new IllegalStateException("题库为空：" + filePath.toAbsolutePath() + "，至少需要一道题目");
        }
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (!ids.add(question.id())) {
                throw new IllegalStateException("题库中存在重复的题目 id：" + question.id());
            }
            if (question.options().isEmpty()) {
                throw new IllegalStateException("题目 " + question.id() + " 没有配置选项");
            }
            if (question.correctOption() < 0 || question.correctOption() >= question.options().size()) {
                throw new IllegalStateException("题目 " + question.id() + " 的正确答案下标越界");
            }
        }
    }

    private void seedDefaultQuestions() {
        List<Question> defaults = List.of(
                new Question(
                        "q-01",
                        "TRACK-01",
                        "这段旋律更适合哪一种场景？",
                        com.jisong.bgm.model.QuestionType.ATMOSPHERE,
                        com.jisong.bgm.model.Difficulty.EASY,
                        List.of("放学后的海边散步", "最终决战前夜", "密室推理现场", "热闹的祭典舞台"),
                        0,
                        "示例作品 A",
                        "明亮、舒缓的和声更接近日常与海边散步的氛围。"
                ),
                new Question(
                        "q-02",
                        "TRACK-02",
                        "这段 BGM 的整体气质更接近？",
                        com.jisong.bgm.model.QuestionType.ATMOSPHERE,
                        com.jisong.bgm.model.Difficulty.EASY,
                        List.of("轻松日常", "紧张追逐", "悲伤离别", "诡异探索"),
                        0,
                        "示例作品 B",
                        "轻快的节奏与简单的旋律动机常用于轻松日常场景。"
                ),
                new Question(
                        "q-03",
                        "TRACK-03",
                        "如果画面里出现樱花和社团教室，这段音乐最可能是什么？",
                        com.jisong.bgm.model.QuestionType.ATMOSPHERE,
                        com.jisong.bgm.model.Difficulty.EASY,
                        List.of("校园日常", "战斗音乐", "悬疑调查", "危机倒计时"),
                        0,
                        "示例作品 C",
                        "校园题材常用清新的钢琴、木管或轻柔弦乐铺陈日常感。"
                ),
                new Question(
                        "q-04",
                        "TRACK-04",
                        "以下哪部作品与这段 BGM 的风格最接近？",
                        com.jisong.bgm.model.QuestionType.WORK,
                        com.jisong.bgm.model.Difficulty.MEDIUM,
                        List.of("夏色心跳", "星屑列车", "月影档案", "雨后的信"),
                        1,
                        "星屑列车",
                        "这是一道示例作品题，后续可替换为真实题库与作品信息。"
                ),
                new Question(
                        "q-05",
                        "TRACK-05",
                        "这段音乐最可能出现在 galgame 的哪类情节？",
                        com.jisong.bgm.model.QuestionType.ATMOSPHERE,
                        com.jisong.bgm.model.Difficulty.MEDIUM,
                        List.of("角色初次相遇", "日常喜剧插曲", "真相揭晓", "片尾回忆"),
                        2,
                        "月影档案",
                        "持续上行的和声与悬念感，适合真相揭晓前后的情节。"
                ),
                new Question(
                        "q-06",
                        "TRACK-06",
                        "如果要从作品名中选出正确答案，你会选？",
                        com.jisong.bgm.model.QuestionType.WORK,
                        com.jisong.bgm.model.Difficulty.HARD,
                        List.of("白昼与纸飞机", "届不到的夏天", "玻璃色的季节", "冬日回廊"),
                        1,
                        "届不到的夏天",
                        "困难题示例，正式活动中替换为来源明确的原声片段。"
                )
        );
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), defaults);
        } catch (IOException exception) {
            throw new IllegalStateException("默认题库初始化失败：" + filePath.toAbsolutePath(), exception);
        }
    }
}
