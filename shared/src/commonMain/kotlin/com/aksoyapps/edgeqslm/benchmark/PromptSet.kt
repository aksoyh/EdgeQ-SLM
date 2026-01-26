package com.aksoyapps.edgeqslm.benchmark

/**
 * Benchmark prompt with metadata.
 */
data class BenchmarkPrompt(
    val id: String,
    val text: String,
    val category: PromptCategory,
    val expectedMinTokens: Int = 10,
    val description: String = ""
)

/**
 * Fixed prompt set for reproducible experiments.
 * Contains 18 prompts across all categories for thesis benchmarking.
 */
object PromptSet {
    
    val ALL_PROMPTS: List<BenchmarkPrompt> = listOf(
        // ========== FACTUAL Q&A ==========
        // Short (simple, direct questions)
        BenchmarkPrompt(
            id = "factual_short_01",
            text = "What is the capital of France?",
            category = PromptCategory.FACTUAL_SHORT,
            expectedMinTokens = 5,
            description = "Simple geography fact"
        ),
        BenchmarkPrompt(
            id = "factual_short_02",
            text = "How many planets are in our solar system?",
            category = PromptCategory.FACTUAL_SHORT,
            expectedMinTokens = 5,
            description = "Basic astronomy fact"
        ),
        
        // Medium (requires more detailed answer)
        BenchmarkPrompt(
            id = "factual_medium_01",
            text = "Explain what photosynthesis is and why it is important for life on Earth.",
            category = PromptCategory.FACTUAL_MEDIUM,
            expectedMinTokens = 30,
            description = "Biology concept explanation"
        ),
        BenchmarkPrompt(
            id = "factual_medium_02",
            text = "What are the main differences between RAM and ROM in computers?",
            category = PromptCategory.FACTUAL_MEDIUM,
            expectedMinTokens = 40,
            description = "Computer science concept"
        ),
        
        // Long (complex topics)
        BenchmarkPrompt(
            id = "factual_long_01",
            text = "Describe the process of how vaccines work in the human body, including the role of antibodies and memory cells in providing immunity against diseases.",
            category = PromptCategory.FACTUAL_LONG,
            expectedMinTokens = 80,
            description = "Complex immunology explanation"
        ),
        
        // ========== INSTRUCTION FOLLOWING ==========
        // Short instructions
        BenchmarkPrompt(
            id = "instruction_short_01",
            text = "List 5 fruits that are red in color.",
            category = PromptCategory.INSTRUCTION_SHORT,
            expectedMinTokens = 10,
            description = "Simple list generation"
        ),
        BenchmarkPrompt(
            id = "instruction_short_02",
            text = "Translate 'Hello, how are you?' to Spanish.",
            category = PromptCategory.INSTRUCTION_SHORT,
            expectedMinTokens = 5,
            description = "Simple translation task"
        ),
        
        // Medium instructions
        BenchmarkPrompt(
            id = "instruction_medium_01",
            text = "Write a Python function that calculates the factorial of a number. Include a docstring explaining what the function does.",
            category = PromptCategory.INSTRUCTION_MEDIUM,
            expectedMinTokens = 40,
            description = "Code generation with documentation"
        ),
        BenchmarkPrompt(
            id = "instruction_medium_02",
            text = "Create a bullet-point summary of the benefits and drawbacks of remote work. Include at least 3 points for each.",
            category = PromptCategory.INSTRUCTION_MEDIUM,
            expectedMinTokens = 50,
            description = "Structured list generation"
        ),
        
        // Long instructions
        BenchmarkPrompt(
            id = "instruction_long_01",
            text = "Write a step-by-step tutorial for beginners explaining how to set up a basic HTML webpage with CSS styling. Include example code for each step and explain what each part does.",
            category = PromptCategory.INSTRUCTION_LONG,
            expectedMinTokens = 100,
            description = "Detailed tutorial with code"
        ),
        
        // ========== CREATIVE ==========
        // Short creative
        BenchmarkPrompt(
            id = "creative_short_01",
            text = "Write a haiku about autumn leaves.",
            category = PromptCategory.CREATIVE_SHORT,
            expectedMinTokens = 10,
            description = "Constrained poetry"
        ),
        BenchmarkPrompt(
            id = "creative_short_02",
            text = "Create a catchy slogan for an eco-friendly water bottle company.",
            category = PromptCategory.CREATIVE_SHORT,
            expectedMinTokens = 8,
            description = "Marketing slogan"
        ),
        
        // Medium creative
        BenchmarkPrompt(
            id = "creative_medium_01",
            text = "Write a short dialogue between a curious child and a wise old tree. The child asks about the meaning of life.",
            category = PromptCategory.CREATIVE_MEDIUM,
            expectedMinTokens = 60,
            description = "Character dialogue"
        ),
        BenchmarkPrompt(
            id = "creative_medium_02",
            text = "Describe a futuristic city in the year 2150 where humans and AI coexist harmoniously. Focus on daily life.",
            category = PromptCategory.CREATIVE_MEDIUM,
            expectedMinTokens = 70,
            description = "Sci-fi world building"
        ),
        
        // Long creative
        BenchmarkPrompt(
            id = "creative_long_01",
            text = "Write the opening paragraph of a mystery novel. Set the scene in a small coastal town on a foggy morning. Introduce a detective who has just arrived to investigate a disappearance. Create atmosphere and intrigue.",
            category = PromptCategory.CREATIVE_LONG,
            expectedMinTokens = 100,
            description = "Novel opening"
        ),
        
        // ========== EDGE CASES ==========
        // Very short (minimal input)
        BenchmarkPrompt(
            id = "edge_vshort_01",
            text = "Hi",
            category = PromptCategory.EDGE_VERY_SHORT,
            expectedMinTokens = 3,
            description = "Minimal greeting"
        ),
        BenchmarkPrompt(
            id = "edge_vshort_02",
            text = "?",
            category = PromptCategory.EDGE_VERY_SHORT,
            expectedMinTokens = 1,
            description = "Single character input"
        ),
        
        // Very long (stress test)
        BenchmarkPrompt(
            id = "edge_vlong_01",
            text = """You are an expert in mobile application development and machine learning optimization. I am working on a thesis project that involves running a Small Language Model (SLM) on mobile devices. The model is quantized using INT8 quantization and runs through llama.cpp. I need you to analyze the following scenario and provide detailed recommendations:

1. What are the key performance bottlenecks when running transformer models on ARM64 mobile CPUs?
2. How does INT8 quantization affect model quality versus speed tradeoffs?
3. What memory optimization techniques should I consider for devices with limited RAM (4-6GB)?
4. How can I measure and report performance metrics in an academically rigorous way?
5. What are the limitations of on-device inference that I should acknowledge in my thesis?

Please provide a comprehensive response covering all these points with specific technical details.""",
            category = PromptCategory.EDGE_VERY_LONG,
            expectedMinTokens = 150,
            description = "Complex multi-part question"
        )
    )
    
    /**
     * Get prompts by category.
     */
    fun getByCategory(category: PromptCategory): List<BenchmarkPrompt> {
        return ALL_PROMPTS.filter { it.category == category }
    }
    
    /**
     * Get a subset of prompts for quick testing.
     */
    fun getQuickTestSet(): List<BenchmarkPrompt> {
        return listOf(
            ALL_PROMPTS.first { it.category == PromptCategory.FACTUAL_SHORT },
            ALL_PROMPTS.first { it.category == PromptCategory.INSTRUCTION_SHORT },
            ALL_PROMPTS.first { it.category == PromptCategory.CREATIVE_SHORT }
        )
    }
    
    /**
     * Get total number of runs for given config.
     * Total = prompts × temperatures × maxTokens × repeats + warmup
     */
    fun calculateTotalRuns(
        config: BenchmarkConfig,
        includeWarmup: Boolean = true
    ): Int {
        val warmupRuns = if (includeWarmup) config.warmupRuns else 0
        return (ALL_PROMPTS.size * config.temperatures.size * config.maxTokensSweep.size * config.repeatsPerCondition) + warmupRuns
    }
}
