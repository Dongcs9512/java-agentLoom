package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.session.Slot;
import io.github.dongcs9512.agentloom.rag.KnowledgeBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 法律咨询演示数据：一个接待 agent（收集信息 + 按场景分派）、三个专业律师 agent，
 * 外加一份劳动法知识库。仅 {@code aia.demo.enabled=true} 时执行，已存在的数据不会覆盖。
 */
@Component
@ConditionalOnProperty(prefix = "agentloom.demo", name = "enabled", havingValue = "true")
public class LegalDemoSeeder implements ApplicationRunner {

    public static final String INTAKE_AGENT = "legal-intake";
    public static final String LABOR_AGENT = "legal-labor";
    public static final String CONTRACT_AGENT = "legal-contract";
    public static final String WELFARE_AGENT = "legal-welfare";
    public static final String HANDOFF_AGENT = "legal-handoff";

    private static final String LAWYER_PROMPT =
            "你是资深劳动法律师。回答必须依据 search_knowledge 检索到的法条与实务要点，"
                    + "先检索再分析；检索不到就明确说明不确定，不要编造法条。"
                    + "输出结构：涉及的法律问题、适用依据、可主张的权益、下一步建议。";

    private final ConfigRepository repo;
    private final ToolRegistry toolRegistry;
    private final AgentRuntime runtime;
    private final ObjectProvider<KnowledgeBase> knowledgeBase;
    private final ScenarioSettings scenarioSettings;

    public LegalDemoSeeder(ConfigRepository repo, ToolRegistry toolRegistry, AgentRuntime runtime,
                           ObjectProvider<KnowledgeBase> knowledgeBase, ScenarioSettings scenarioSettings) {
        this.repo = repo;
        this.toolRegistry = toolRegistry;
        this.runtime = runtime;
        this.knowledgeBase = knowledgeBase;
        this.scenarioSettings = scenarioSettings;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAgents();
        seedKnowledge();
    }

    private void seedAgents() {
        seedAgent(LABOR_AGENT, LAWYER_PROMPT + "你专注劳动争议：辞退、欠薪、加班费、经济补偿与赔偿金。");
        seedAgent(CONTRACT_AGENT, LAWYER_PROMPT + "你专注劳动合同与竞业限制：试用期、违约金、保密与竞业协议效力。");
        seedAgent(WELFARE_AGENT, LAWYER_PROMPT + "你专注社保与工伤：社会保险缴纳、工伤认定、女职工三期保护、年休假。");
        seedAgent(HANDOFF_AGENT, "你是人工客服转接专员。只输出一句话，告知用户本案已转交人工律师跟进，"
                + "请保持电话畅通、留意工单短信，不要给任何法律分析。");

        if (!agentExists(INTAKE_AGENT)) {
            repo.saveAgent(new AgentConfig(
                    INTAKE_AGENT, intakePrompt(), "deepseek-chat", List.of("*"), List.of(), 0.3, 800,
                    List.of(
                            new Slot("incident", "具体是什么事？比如被辞退、欠薪、加班费、工伤", true),
                            new Slot("when", "事情大概发生在什么时候？", true),
                            new Slot("location", "工作地在哪个城市？", true),
                            new Slot("detail", "能简单说说经过吗？比如有没有签合同、有没有书面通知", false),
                            new Slot("amount", "涉及的金额大概是多少？比如欠薪两万", false))));
        }

        if (!scenarioSettings.enabled() && toolRegistry.get("delegate_to_" + LABOR_AGENT).isEmpty()) {
            toolRegistry.register(runtime.subAgentTool(LABOR_AGENT, "把整理好的劳动争议案情交给专业律师做法律分析"));
        }
    }

    private String intakePrompt() {
        String delegation = scenarioSettings.enabled()
                ? "信息收集齐全后，调用 delegate_to_specialist 把完整情况交出去，系统会自动匹配对应的专业律师。"
                : "信息收集齐全后，调用 delegate_to_" + LABOR_AGENT + " 把完整情况交给专业律师分析。";
        return "你是法律咨询的接待顾问。用户一开口描述案情，先立刻调用 record_case_info "
                + "把 incident 记下来（用户说的就是事实，不要先追问再记），"
                + "再按【信息收集】逐项了解其他情况，一次只问一到两项，语气自然亲切，不要像填表。"
                + "用户只要提到金额（欠薪多少、赔偿要多少），立刻调用 record_case_info 记录 amount，"
                + "并换成阿拉伯数字（比如用户说两万就记 20000），否则流程判断不了。"
                + delegation
                + "最后把律师的结论用通俗的话转述给用户。";
    }

    private void seedAgent(String id, String systemPrompt) {
        if (agentExists(id)) {
            return;
        }
        repo.saveAgent(new AgentConfig(id, systemPrompt, "deepseek-chat", List.of("*"), List.of(), 0.2, 800,
                List.of()));
    }

    private void seedKnowledge() {
        KnowledgeBase kb = knowledgeBase.getIfAvailable();
        if (kb == null) {
            return;
        }
        ingest(kb, "labor-contract", "劳动合同法第十条：建立劳动关系应当订立书面劳动合同。"
                + "用人单位自用工之日起超过一个月不满一年未与劳动者订立书面劳动合同的，"
                + "应当向劳动者每月支付二倍的工资，最多支付十一个月。");
        ingest(kb, "labor-dismissal", "劳动合同法第八十七条：用人单位违反本法规定解除或者终止劳动合同的，"
                + "应当依照经济补偿标准的二倍向劳动者支付赔偿金。经济补偿按劳动者在本单位工作的年限，"
                + "每满一年支付一个月工资，六个月以上不满一年的按一年计算，不满六个月的支付半个月工资。");
        ingest(kb, "labor-arbitration", "劳动争议调解仲裁法第二十七条：劳动争议申请仲裁的时效期间为一年，"
                + "从当事人知道或者应当知道其权利被侵害之日起计算。"
                + "劳动关系存续期间因拖欠劳动报酬发生争议的，不受一年时效限制；"
                + "但劳动关系终止的，应当自终止之日起一年内提出。");
        ingest(kb, "labor-overtime", "劳动法第四十四条：安排劳动者延长工作时间的，支付不低于工资的百分之一百五十的工资报酬；"
                + "休息日安排工作又不能安排补休的，支付不低于百分之二百的工资报酬；"
                + "法定休假日安排工作的，支付不低于百分之三百的工资报酬。");
        ingest(kb, "labor-wages", "劳动合同法第三十条：用人单位应当按照劳动合同约定和国家规定，"
                + "向劳动者及时足额支付劳动报酬。用人单位拖欠或者未足额支付劳动报酬的，"
                + "劳动者可以依法向当地人民法院申请支付令，人民法院应当依法发出支付令。");
    }

    private void ingest(KnowledgeBase kb, String docId, String text) {
        if (kb.search(text.substring(0, Math.min(12, text.length())), 1).stream()
                .noneMatch(hit -> docId.equals(hit.metadata().get("docId")))) {
            kb.addDocument(docId, text);
        }
    }

    private boolean agentExists(String agentId) {
        return repo.loadAgents().stream().anyMatch(a -> agentId.equals(a.id()));
    }

    /** 演示用的 agent 列表，便于文档与测试引用。 */
    public static Map<String, String> agents() {
        return Map.of(
                INTAKE_AGENT, "接待与信息收集",
                LABOR_AGENT, "劳动争议（辞退/欠薪/加班费）",
                HANDOFF_AGENT, "人工转接（大额案件）",
                CONTRACT_AGENT, "合同与竞业限制",
                WELFARE_AGENT, "社保与工伤");
    }
}
