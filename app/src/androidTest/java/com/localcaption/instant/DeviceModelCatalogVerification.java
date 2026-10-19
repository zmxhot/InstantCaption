package com.localcaption.instant;

import android.content.Context;
import java.io.InputStream;
import org.json.*;

/** Exercises the actual JSON parser and download eligibility without spoofing the phone. */
final class DeviceModelCatalogVerification {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static DeviceProfile device(DeviceModelCatalog c,String soc,String maker,String board){return new DeviceProfile(c,36,new String[]{"arm64-v8a"},soc,maker,board,12_000_000_000L,false);}
    static JSONObject run(Context context)throws Exception{
        String raw;try(InputStream in=context.getAssets().open(DeviceModelCatalog.ASSET)){raw=NpuRecognizer.readText(in);}
        DeviceModelCatalog catalog=DeviceModelCatalog.parse(raw);
        String[][] cases={
            {"SM8750","","","sm8750"},{"sm8750-ac","","","sm8750"},{"QTI SM8750","","","sm8750"},
            {"Qualcomm Snapdragon 8 Elite","","","sm8750"},{"SM8850-5-AC","","","sm8850"},
            {"Snapdragon 8 Elite Gen 5","","","sm8850"},{"QUALCOMM SM8850","","","sm8850"},
            {"SM8650","","","sm8650"},{"SM8550","","","sm8550"},{"SM8350","","","sm8350"},{"SM8450","","","sm8450"},{"SM8475","","","sm8475"},{"SM8845","","","portable"},
            {"SM8735","","","portable"},{"SM7750","","","portable"},{"SM7675","","","portable"},
            {"SM6650","","","portable"},{"SM4450","","","portable"},{"Dimensity 9500","","","portable"},
            {"MediaTek Dimensity 9400+","","","mt6991"},{"Dimensity 8400","","","portable"},
            {"MT6991","","","mt6991"},{"MT6989Z/CZA","","","mt6989"},{"MT6990","","","portable"},{"MT6993","","","portable"},{"MT6893Z/CZA","","","portable"},{"Helio G99","","","portable"},
            {"Exynos 2600","","","portable"},{"Exynos 1580","","","portable"},{"S5E9945","","","portable"},
            {"Google Tensor G5","","","tensor-g5"},{"Tensor G6","","","tensor-g6"},{"Tensor G4","","","portable"},{"GS201","","","portable"},
            {"Kirin 9020","","","portable"},{"HiSilicon Kirin 990","","","portable"},
            {"UNISOC T8200","","","portable"},{"UMS9230","","","portable"},
            {"","MediaTek","","portable"},{"","Google","","portable"},{"","HiSilicon","","portable"},
            {"","UNISOC","","portable"},{"","Qualcomm","qcom","portable"},{"","","mt6785","portable"},
            {"","","exynos9820","portable"},{"UNKNOWN-NEW-CHIP","","","portable"},
            {"SM87500","","","portable"},{"SM8850/SM8750","","","portable"}
        };
        for(String[] row:cases){
            DeviceProfile d=device(catalog,row[0],row[1],row[2]);require(d.modelProfile.id.equals(row[3]),"Wrong profile for "+row[0]+" / "+row[1]+" / "+row[2]);
            boolean npu=!row[3].equals("portable")&&BuildConfig.NPU_BUILD;
            require(d.npuEligible==npu,"Wrong runtime exposure: "+row[0]);
            int speechCount=!npu?3:row[3].equals("sm8350")?4:(row[3].startsWith("tensor-")||row[3].startsWith("mt"))?3:(row[3].equals("sm8750")||row[3].equals("sm8850"))?7:6;
            require(d.downloadableModels("en").size()==speechCount,"Wrong speech list: "+row[0]);
            require(d.supportsText("opus"),"OPUS missing: "+row[0]);
            for(QwenModel q:QwenModel.values()){
                boolean textEligible=q==QwenModel.HYMT2||npu&&(q==QwenModel.GEMMA3?(row[3].equals("sm8750")||row[3].equals("sm8650")||row[3].equals("sm8550")||row[3].startsWith("mt")):q==QwenModel.GEMMA4?(row[3].equals("sm8750")||row[3].startsWith("tensor-")):(row[3].equals("sm8750")||row[3].equals("sm8850")));
                require(q.isSupported(d)==textEligible,"Wrong text list: "+row[0]+" / "+q.id);
            }
        }
        require(device(catalog,"SM8750","Samsung","exynos9820").npuChip.equals("SM8750"),"Vendor overrode exact SoC");
        require(catalog.match("Tensor G5","Samsung","").id.equals("tensor-g5"),"Vendor overrode Tensor");
        require(catalog.match("","","mt6785").id.equals("mediatek"),"Older Android hardware fallback missing");
        require(catalog.match("UNKNOWN-NEW-CHIP","","").source.equals("fallback"),"Unknown chip not handled");
        DeviceProfile low=new DeviceProfile(catalog,29,new String[]{"arm64-v8a"},"MT6785","","",3_000_000_000L,true);
        require(low.recommendedModel("en")==NpuModel.WHISPER_TINY,"Low-RAM recommendation not honored");
        require(device(catalog,"Dimensity 9500","","").recommendedModel("en")==NpuModel.WHISPER_BASE,"Higher-RAM recommendation not honored");
        // Configuration changes alone must affect availability, ordering and new SoC aliases.
        JSONObject changed=new JSONObject(raw);JSONArray models=changed.getJSONArray("profiles").getJSONObject(2).getJSONArray("models");
        for(int i=models.length()-1;i>=0;i--){JSONObject model=models.getJSONObject(i);if(model.getString("id").equals(QwenModel.LARGE.id))models.remove(i);else if(model.getString("id").equals("x-asr"))model.put("priority",50);}
        changed.getJSONArray("chipsets").put(new JSONObject().put("id","config-only-chip").put("name","Configuration test").put("profile","sm8850").put("match",new JSONObject().put("soc",new JSONArray().put("CONFIG_ONLY_SOC"))));
        DeviceModelCatalog edited=DeviceModelCatalog.parse(changed.toString());DeviceProfile example=device(edited,"CONFIG_ONLY_SOC","","");
        require(example.modelProfile.id.equals("sm8850"),"JSON-only alias ignored");
        require(!example.supportsText(QwenModel.LARGE.id),"Removed text model still offered");
        if(BuildConfig.NPU_BUILD)require(example.recommendedModel("en")==NpuModel.NEMOTRON,"JSON model priority ignored");
        JSONObject invalid=new JSONObject(raw);invalid.getJSONArray("profiles").getJSONObject(0).getJSONArray("models").getJSONObject(0).put("manifest","../outside.json");rejected(invalid);
        invalid=new JSONObject(raw);invalid.getJSONArray("chipsets").getJSONObject(0).getJSONObject("match").put("socManufacturer",new JSONArray().put("QUALCOMM"));rejected(invalid);
        invalid=new JSONObject(raw);invalid.getJSONArray("profiles").getJSONObject(0).getJSONArray("models").getJSONObject(0).put("id","unimplemented-model");rejected(invalid);
        return new JSONObject().put("passed",true).put("hardwareProfilesChecked",cases.length).put("jsonChipRules",catalog.chips.size())
            .put("configOnlyModelRemovalPriorityAndChipAddition",true).put("noPhysicalPhoneWhitelist",true).put("invalidCatalogsRejected",true);
    }
    private static void rejected(JSONObject data){boolean rejected=false;try{DeviceModelCatalog.parse(data.toString());}catch(Exception expected){rejected=true;}require(rejected,"Invalid catalog accepted");}
}
